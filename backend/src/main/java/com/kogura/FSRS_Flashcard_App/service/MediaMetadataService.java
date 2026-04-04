package com.kogura.FSRS_Flashcard_App.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.model.MediaMetadata;
import com.kogura.FSRS_Flashcard_App.repository.MediaMetadataRepository;

@Service
public class MediaMetadataService {

  private static final Duration REFRESH_BUFFER = Duration.ofMinutes(5);
  private static final long URL_TTL_SECONDS = 600; // 10 minutes

  private final MediaMetadataRepository mediaMetadataRepository;
  private final S3Service s3Service;

  public MediaMetadataService(MediaMetadataRepository mediaMetadataRepository, S3Service s3Service) {
    this.mediaMetadataRepository = mediaMetadataRepository;
    this.s3Service = s3Service;
  }

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

  public void refreshDownloadUrlsForFlashcards(List<Flashcard> flashcards) {
    if (flashcards == null) return;

    for (Flashcard flashcard : flashcards) {
      refreshDownloadUrlIfNeeded(flashcard.getQuestionMediaMetadata());
      refreshDownloadUrlIfNeeded(flashcard.getAnswerMediaMetadata());
    }
  }
}
