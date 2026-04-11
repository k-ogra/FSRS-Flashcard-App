package com.kogura.FSRS_Flashcard_App.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.model.MediaMetadata;
import com.kogura.FSRS_Flashcard_App.repository.MediaMetadataRepository;

/**
 * Manages presigned download URLs for media metadata — refreshes URLs proactively
 * before they expire, copies metadata when flashcards are duplicated, and batch-refreshes
 * URLs across flashcard lists.
 */
@Service
public class MediaMetadataService {

  /** How far before actual expiry to proactively refresh a URL (5 minutes). */
  static final Duration REFRESH_BUFFER = Duration.ofMinutes(5);

  /** Time-to-live for presigned download URLs in seconds (10 minutes). */
  static final long URL_TTL_SECONDS = 600; // 10 minutes

  /** Repository for persisting media metadata updates. */
  private final MediaMetadataRepository mediaMetadataRepository;

  /** S3 service used to generate fresh presigned download URLs. */
  private final S3Service s3Service;

  /**
   * Constructs the service with required dependencies.
   *
   * @param mediaMetadataRepository media metadata persistence
   * @param s3Service               S3 presigned URL generation
   */
  public MediaMetadataService(MediaMetadataRepository mediaMetadataRepository, S3Service s3Service) {
    this.mediaMetadataRepository = mediaMetadataRepository;
    this.s3Service = s3Service;
  }

  /**
   * Refreshes the presigned download URL on the given metadata if it is expired or
   * about to expire (within the {@link #REFRESH_BUFFER}). No-ops safely when the
   * metadata is {@code null} or has no S3 key. Persists the updated metadata when
   * a refresh occurs.
   *
   * @param meta the media metadata to check and potentially refresh; may be {@code null}
   * @return the same metadata instance (possibly updated), or {@code null} if input was {@code null}
   */
  public MediaMetadata refreshDownloadUrlIfNeeded(MediaMetadata meta) {
    if (meta == null || meta.getS3Key() == null) {
      return meta;
    }

    Instant now = Instant.now();
    if (meta.getUrlExpiresAt() == null || meta.getUrlExpiresAt().minus(REFRESH_BUFFER).isBefore(now)) {
      String freshUrl = s3Service.createPresignedDownloadUrl(meta.getS3Key());
      meta.setPresignedDownloadUrl(freshUrl);
      meta.setUrlExpiresAt(now.plusSeconds(URL_TTL_SECONDS));
      mediaMetadataRepository.save(meta);
    }

    return meta;
  }

  /**
   * Attaches a {@link MediaMetadata} row to the given flashcard's question or answer side.
   * Idempotent — if the side already has metadata pointing at the same {@code s3Key}, returns
   * the existing row unchanged. Otherwise builds a fresh {@code MediaMetadata} with a
   * presigned download URL and associates it with the flashcard. The caller is responsible
   * for persisting the flashcard (cascade will save the new metadata row).
   *
   * @param flashcard  the flashcard to attach media to
   * @param s3Key      the S3 object key of the uploaded file
   * @param fileName   the original filename to display to the user
   * @param isQuestion {@code true} for the question side, {@code false} for the answer side
   * @return the attached metadata (either the pre-existing match or a freshly built instance)
   */
  public MediaMetadata attachMediaToFlashcard(Flashcard flashcard, String s3Key, String fileName, boolean isQuestion) {
    MediaMetadata existing = isQuestion ? flashcard.getQuestionMediaMetadata() : flashcard.getAnswerMediaMetadata();
    if (existing != null && s3Key.equals(existing.getS3Key())) {
      return existing;
    }

    Instant now = Instant.now();
    String presignedUrl = s3Service.createPresignedDownloadUrl(s3Key);

    MediaMetadata meta = new MediaMetadata();
    meta.setName(fileName);
    meta.setS3Key(s3Key);
    meta.setPresignedDownloadUrl(presignedUrl);
    meta.setUrlExpiresAt(now.plusSeconds(URL_TTL_SECONDS));

    if (isQuestion) {
      flashcard.setQuestionMediaMetadata(meta);
    } else {
      flashcard.setAnswerMediaMetadata(meta);
    }
    return meta;
  }

  /**
   * Creates a new {@link MediaMetadata} entity by copying the source's name and assigning
   * a new S3 key. Generates a fresh presigned download URL for the new key and persists
   * the copy.
   *
   * @param source   the original metadata to copy the name from
   * @param newS3Key the S3 key for the copied object
   * @return the persisted copy with a fresh URL and expiry
   */
  public MediaMetadata copyMediaMetadata(MediaMetadata source, String newS3Key) {
    Instant now = Instant.now();
    String freshUrl = s3Service.createPresignedDownloadUrl(newS3Key);

    MediaMetadata copy = new MediaMetadata();
    copy.setName(source.getName());
    copy.setS3Key(newS3Key);
    copy.setPresignedDownloadUrl(freshUrl);
    copy.setUrlExpiresAt(now.plusSeconds(URL_TTL_SECONDS));
    return mediaMetadataRepository.save(copy);
  }

  /**
   * Batch-refreshes presigned download URLs for the question and answer media of each
   * flashcard in the list. No-ops safely when the list is {@code null} or when individual
   * flashcards have no media attached.
   *
   * @param flashcards the flashcards whose media URLs should be refreshed; may be {@code null}
   */
  public void refreshDownloadUrlsForFlashcards(List<Flashcard> flashcards) {
    if (flashcards == null) return;

    for (Flashcard flashcard : flashcards) {
      refreshDownloadUrlIfNeeded(flashcard.getQuestionMediaMetadata());
      refreshDownloadUrlIfNeeded(flashcard.getAnswerMediaMetadata());
    }
  }
}
