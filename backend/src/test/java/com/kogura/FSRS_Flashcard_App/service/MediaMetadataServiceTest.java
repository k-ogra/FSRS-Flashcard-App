package com.kogura.FSRS_Flashcard_App.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.model.MediaMetadata;
import com.kogura.FSRS_Flashcard_App.repository.MediaMetadataRepository;

@ExtendWith(MockitoExtension.class)
public class MediaMetadataServiceTest {

  /** Mock for media metadata persistence. */
  @Mock
  private MediaMetadataRepository mediaMetadataRepository;

  /** Mock for S3 presigned URL generation. */
  @Mock
  private S3Service s3Service;

  /** The service under test. */
  private MediaMetadataService mediaMetadataService;

  @BeforeEach
  void setUp() {
    mediaMetadataService = new MediaMetadataService(mediaMetadataRepository, s3Service);
  }

  // ── Helper methods ─────────────────────────────────────────

  private MediaMetadata metaWithExpiry(String s3Key, Instant expiresAt) {
    MediaMetadata meta = new MediaMetadata();
    meta.setId(1L);
    meta.setS3Key(s3Key);
    meta.setName("file.jpg");
    meta.setPresignedDownloadUrl("https://s3/old-url");
    meta.setUrlExpiresAt(expiresAt);
    return meta;
  }

  private Flashcard flashcardWithMedia(MediaMetadata questionMeta, MediaMetadata answerMeta) {
    Flashcard fc = new Flashcard();
    fc.setId(1L);
    fc.setQuestionMediaMetadata(questionMeta);
    fc.setAnswerMediaMetadata(answerMeta);
    return fc;
  }

  // ── refreshDownloadUrlIfNeeded ─────────────────────────────

  /**
   * Verifies that passing {@code null} metadata returns {@code null} without calling
   * S3 or the repository.
   */
  @Test
  void refreshDownloadUrlIfNeeded_nullMeta_returnsNull() {
    MediaMetadata result = mediaMetadataService.refreshDownloadUrlIfNeeded(null);

    assertThat(result).isNull();
    verify(s3Service, never()).createPresignedDownloadUrl(any());
    verify(mediaMetadataRepository, never()).save(any());
  }

  /**
   * Verifies that metadata with a {@code null} S3 key is returned as-is without
   * attempting a URL refresh.
   */
  @Test
  void refreshDownloadUrlIfNeeded_nullS3Key_returnsMetaUnchanged() {
    MediaMetadata meta = new MediaMetadata();
    meta.setS3Key(null);
    meta.setName("orphaned.jpg");

    MediaMetadata result = mediaMetadataService.refreshDownloadUrlIfNeeded(meta);

    assertThat(result).isSameAs(meta);
    verify(s3Service, never()).createPresignedDownloadUrl(any());
    verify(mediaMetadataRepository, never()).save(any());
  }

  /**
   * Verifies that when {@code urlExpiresAt} is {@code null} (never fetched), a fresh
   * presigned URL is generated, the expiry is set, and the metadata is persisted.
   */
  @Test
  void refreshDownloadUrlIfNeeded_nullExpiry_refreshesUrl() {
    MediaMetadata meta = new MediaMetadata();
    meta.setS3Key("media/test.jpg");
    meta.setUrlExpiresAt(null);

    when(s3Service.createPresignedDownloadUrl("media/test.jpg"))
        .thenReturn("https://s3/fresh-url");
    when(mediaMetadataRepository.save(any(MediaMetadata.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    MediaMetadata result = mediaMetadataService.refreshDownloadUrlIfNeeded(meta);

    assertThat(result.getPresignedDownloadUrl()).isEqualTo("https://s3/fresh-url");
    assertThat(result.getUrlExpiresAt()).isNotNull();
    verify(mediaMetadataRepository).save(meta);
  }

  /**
   * Verifies that when the URL has expired (expiry is in the past), a new presigned
   * URL is generated and persisted.
   */
  @Test
  void refreshDownloadUrlIfNeeded_expiredUrl_refreshesUrl() {
    MediaMetadata meta = metaWithExpiry("media/old.jpg", Instant.now().minus(Duration.ofMinutes(10)));

    when(s3Service.createPresignedDownloadUrl("media/old.jpg"))
        .thenReturn("https://s3/refreshed-url");
    when(mediaMetadataRepository.save(any(MediaMetadata.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    MediaMetadata result = mediaMetadataService.refreshDownloadUrlIfNeeded(meta);

    assertThat(result.getPresignedDownloadUrl()).isEqualTo("https://s3/refreshed-url");
    verify(mediaMetadataRepository).save(meta);
  }

  /**
   * Verifies that when the URL is about to expire (within the 5-minute refresh buffer),
   * it is proactively refreshed.
   */
  @Test
  void refreshDownloadUrlIfNeeded_withinRefreshBuffer_refreshesUrl() {
    // Expires within the refresh buffer — should trigger a proactive refresh
    Duration insideBuffer = MediaMetadataService.REFRESH_BUFFER.minus(Duration.ofMinutes(2));
    MediaMetadata meta = metaWithExpiry("media/soon.jpg", Instant.now().plus(insideBuffer));

    when(s3Service.createPresignedDownloadUrl("media/soon.jpg"))
        .thenReturn("https://s3/buffered-url");
    when(mediaMetadataRepository.save(any(MediaMetadata.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    MediaMetadata result = mediaMetadataService.refreshDownloadUrlIfNeeded(meta);

    assertThat(result.getPresignedDownloadUrl()).isEqualTo("https://s3/buffered-url");
    verify(mediaMetadataRepository).save(meta);
  }

  /**
   * Verifies that when the URL is still valid (well beyond the 5-minute refresh buffer),
   * no refresh is performed and the metadata is returned unchanged.
   */
  @Test
  void refreshDownloadUrlIfNeeded_stillValid_doesNotRefresh() {
    // Expires well beyond the refresh buffer — no refresh needed
    Duration outsideBuffer = MediaMetadataService.REFRESH_BUFFER.plus(Duration.ofMinutes(3));
    MediaMetadata meta = metaWithExpiry("media/valid.jpg", Instant.now().plus(outsideBuffer));

    MediaMetadata result = mediaMetadataService.refreshDownloadUrlIfNeeded(meta);

    assertThat(result.getPresignedDownloadUrl()).isEqualTo("https://s3/old-url");
    verify(s3Service, never()).createPresignedDownloadUrl(any());
    verify(mediaMetadataRepository, never()).save(any());
  }

  /**
   * Verifies that after a refresh, {@code urlExpiresAt} is set to approximately
   * 10 minutes (600 seconds) from now.
   */
  @Test
  void refreshDownloadUrlIfNeeded_setsExpiryTo10Minutes() {
    MediaMetadata meta = new MediaMetadata();
    meta.setS3Key("media/ttl.jpg");
    meta.setUrlExpiresAt(null);

    when(s3Service.createPresignedDownloadUrl("media/ttl.jpg"))
        .thenReturn("https://s3/url");
    when(mediaMetadataRepository.save(any(MediaMetadata.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    Instant before = Instant.now();
    mediaMetadataService.refreshDownloadUrlIfNeeded(meta);
    Instant after = Instant.now();

    // urlExpiresAt should be ~URL_TTL_SECONDS from now
    long ttl = MediaMetadataService.URL_TTL_SECONDS;
    assertThat(meta.getUrlExpiresAt()).isAfterOrEqualTo(before.plusSeconds(ttl));
    assertThat(meta.getUrlExpiresAt()).isBeforeOrEqualTo(after.plusSeconds(ttl));
  }

  // ── copyMediaMetadata ─────────────────────────────────────

  /**
   * Verifies that copying media metadata creates a new entity with the source's name,
   * the new S3 key, a fresh presigned URL, and an expiry timestamp.
   */
  @Test
  void copyMediaMetadata_createsNewMetadataWithFreshUrl() {
    MediaMetadata source = new MediaMetadata();
    source.setName("photo.png");
    source.setS3Key("old/photo.png");

    when(s3Service.createPresignedDownloadUrl("new/photo.png"))
        .thenReturn("https://s3/copy-url");
    when(mediaMetadataRepository.save(any(MediaMetadata.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    MediaMetadata result = mediaMetadataService.copyMediaMetadata(source, "new/photo.png");

    assertThat(result.getName()).isEqualTo("photo.png");
    assertThat(result.getS3Key()).isEqualTo("new/photo.png");
    assertThat(result.getPresignedDownloadUrl()).isEqualTo("https://s3/copy-url");
    assertThat(result.getUrlExpiresAt()).isNotNull();
  }

  /**
   * Verifies that the copied metadata is persisted via the repository and the returned
   * object is the saved entity.
   */
  @Test
  void copyMediaMetadata_persistsCopy() {
    MediaMetadata source = new MediaMetadata();
    source.setName("doc.pdf");
    source.setS3Key("old/doc.pdf");

    when(s3Service.createPresignedDownloadUrl("new/doc.pdf"))
        .thenReturn("https://s3/url");
    when(mediaMetadataRepository.save(any(MediaMetadata.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    mediaMetadataService.copyMediaMetadata(source, "new/doc.pdf");

    ArgumentCaptor<MediaMetadata> captor = ArgumentCaptor.forClass(MediaMetadata.class);
    verify(mediaMetadataRepository).save(captor.capture());
    MediaMetadata saved = captor.getValue();
    assertThat(saved.getS3Key()).isEqualTo("new/doc.pdf");
    assertThat(saved.getName()).isEqualTo("doc.pdf");
  }

  /**
   * Verifies that the copy does not share identity with the source — it is a distinct
   * new entity (no ID carried over from the source).
   */
  @Test
  void copyMediaMetadata_doesNotCopySourceId() {
    MediaMetadata source = new MediaMetadata();
    source.setId(99L);
    source.setName("img.jpg");
    source.setS3Key("old/img.jpg");

    when(s3Service.createPresignedDownloadUrl("new/img.jpg"))
        .thenReturn("https://s3/url");
    when(mediaMetadataRepository.save(any(MediaMetadata.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    MediaMetadata result = mediaMetadataService.copyMediaMetadata(source, "new/img.jpg");

    assertThat(result.getId()).isNull();
  }

  // ── refreshDownloadUrlsForFlashcards ──────────────────────

  /**
   * Verifies that passing a {@code null} flashcard list is a safe no-op — no S3 calls
   * or repository saves are made.
   */
  @Test
  void refreshDownloadUrlsForFlashcards_nullList_doesNothing() {
    mediaMetadataService.refreshDownloadUrlsForFlashcards(null);

    verify(s3Service, never()).createPresignedDownloadUrl(any());
    verify(mediaMetadataRepository, never()).save(any());
  }

  /**
   * Verifies that passing an empty flashcard list is a safe no-op.
   */
  @Test
  void refreshDownloadUrlsForFlashcards_emptyList_doesNothing() {
    mediaMetadataService.refreshDownloadUrlsForFlashcards(Collections.emptyList());

    verify(s3Service, never()).createPresignedDownloadUrl(any());
    verify(mediaMetadataRepository, never()).save(any());
  }

  /**
   * Verifies that for flashcards with expired media on both sides, both question and
   * answer URLs are refreshed.
   */
  @Test
  void refreshDownloadUrlsForFlashcards_refreshesBothSides() {
    MediaMetadata qMeta = metaWithExpiry("q.jpg", Instant.now().minus(Duration.ofMinutes(1)));
    MediaMetadata aMeta = metaWithExpiry("a.mp3", Instant.now().minus(Duration.ofMinutes(1)));
    Flashcard fc = flashcardWithMedia(qMeta, aMeta);

    when(s3Service.createPresignedDownloadUrl("q.jpg")).thenReturn("https://s3/q-new");
    when(s3Service.createPresignedDownloadUrl("a.mp3")).thenReturn("https://s3/a-new");
    when(mediaMetadataRepository.save(any(MediaMetadata.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    mediaMetadataService.refreshDownloadUrlsForFlashcards(List.of(fc));

    assertThat(qMeta.getPresignedDownloadUrl()).isEqualTo("https://s3/q-new");
    assertThat(aMeta.getPresignedDownloadUrl()).isEqualTo("https://s3/a-new");
    verify(s3Service, times(2)).createPresignedDownloadUrl(any());
  }

  /**
   * Verifies that flashcards with no media metadata (both sides {@code null}) are
   * safely skipped without any S3 calls.
   */
  @Test
  void refreshDownloadUrlsForFlashcards_noMedia_skipsGracefully() {
    Flashcard fc = flashcardWithMedia(null, null);

    mediaMetadataService.refreshDownloadUrlsForFlashcards(List.of(fc));

    verify(s3Service, never()).createPresignedDownloadUrl(any());
    verify(mediaMetadataRepository, never()).save(any());
  }

  /**
   * Verifies that when processing multiple flashcards, all cards with expired media
   * have their URLs refreshed.
   */
  @Test
  void refreshDownloadUrlsForFlashcards_multipleCards_refreshesAll() {
    MediaMetadata meta1 = metaWithExpiry("img1.jpg", Instant.now().minus(Duration.ofMinutes(1)));
    MediaMetadata meta2 = metaWithExpiry("img2.jpg", Instant.now().minus(Duration.ofMinutes(1)));
    Flashcard fc1 = flashcardWithMedia(meta1, null);
    Flashcard fc2 = flashcardWithMedia(null, meta2);

    when(s3Service.createPresignedDownloadUrl("img1.jpg")).thenReturn("https://s3/new1");
    when(s3Service.createPresignedDownloadUrl("img2.jpg")).thenReturn("https://s3/new2");
    when(mediaMetadataRepository.save(any(MediaMetadata.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    mediaMetadataService.refreshDownloadUrlsForFlashcards(List.of(fc1, fc2));

    assertThat(meta1.getPresignedDownloadUrl()).isEqualTo("https://s3/new1");
    assertThat(meta2.getPresignedDownloadUrl()).isEqualTo("https://s3/new2");
    verify(s3Service, times(2)).createPresignedDownloadUrl(any());
  }
}
