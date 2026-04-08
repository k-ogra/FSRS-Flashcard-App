package com.kogura.FSRS_Flashcard_App.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.kogura.FSRS_Flashcard_App.config.S3Buckets;
import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.model.MediaMetadata;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.S3Error;

@ExtendWith(MockitoExtension.class)
public class S3ServiceTest {

  private static final String BUCKET_NAME = "test-bucket";

  @Mock
  private S3Client s3Client;

  @Mock
  private S3Buckets s3Buckets;

  private S3Service s3Service;

  @BeforeEach
  void setUp() {
    s3Service = new S3Service(s3Buckets, s3Client);
  }

  // ── copyObject ─────────────────────────────────────────────

  /**
   * Verifies that {@code copyObject} builds a {@link CopyObjectRequest} with the correct
   * source/destination bucket and keys, uses {@code REPLACE} metadata directive, and passes
   * an empty metadata map.
   */
  @Test
  void copyObject_buildsRequestWithCorrectBucketAndKeys() {
    when(s3Buckets.getBucketName()).thenReturn(BUCKET_NAME);
    when(s3Client.copyObject(any(CopyObjectRequest.class)))
        .thenReturn(CopyObjectResponse.builder().build());

    s3Service.copyObject("source/key.jpg", "destination/key.jpg");

    ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
    verify(s3Client).copyObject(captor.capture());
    CopyObjectRequest request = captor.getValue();

    assertThat(request.sourceBucket()).isEqualTo(BUCKET_NAME);
    assertThat(request.destinationBucket()).isEqualTo(BUCKET_NAME);
    assertThat(request.sourceKey()).isEqualTo("source/key.jpg");
    assertThat(request.destinationKey()).isEqualTo("destination/key.jpg");
    assertThat(request.metadataDirective()).isEqualTo(MetadataDirective.REPLACE);
    assertThat(request.metadata()).isEmpty();
  }

  // ── deleteObjects ──────────────────────────────────────────

  /**
   * Verifies that passing a {@code null} key list is a safe no-op — no S3 delete call is made.
   */
  @Test
  void deleteObjects_nullList_doesNothing() {
    s3Service.deleteObjects(null);
    verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
  }

  /**
   * Verifies that passing an empty key list is a safe no-op — no S3 delete call is made.
   */
  @Test
  void deleteObjects_emptyList_doesNothing() {
    s3Service.deleteObjects(Collections.emptyList());
    verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
  }

  /**
   * Verifies that a small list of keys (under the 1000-key batch limit) is sent in a single
   * {@link DeleteObjectsRequest} containing all keys in order.
   */
  @Test
  void deleteObjects_singleBatch_sendsOneRequestWithAllKeys() {
    when(s3Buckets.getBucketName()).thenReturn(BUCKET_NAME);
    when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenReturn(DeleteObjectsResponse.builder().build());

    List<String> keys = List.of("a.jpg", "b.jpg", "c.jpg");
    s3Service.deleteObjects(keys);

    ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
    verify(s3Client, times(1)).deleteObjects(captor.capture());
    DeleteObjectsRequest request = captor.getValue();

    assertThat(request.bucket()).isEqualTo(BUCKET_NAME);
    assertThat(request.delete().objects()).hasSize(3);
    assertThat(request.delete().objects())
        .extracting("key")
        .containsExactly("a.jpg", "b.jpg", "c.jpg");
    assertThat(request.delete().quiet()).isFalse();
  }

  /**
   * Verifies that exactly 1000 keys (the batch size boundary) results in a single delete request,
   * not two.
   */
  @Test
  void deleteObjects_exactlyBatchSize_sendsSingleRequest() {
    when(s3Buckets.getBucketName()).thenReturn(BUCKET_NAME);
    when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenReturn(DeleteObjectsResponse.builder().build());

    List<String> keys = IntStream.range(0, 1000)
        .mapToObj(i -> "key-" + i)
        .toList();

    s3Service.deleteObjects(keys);

    verify(s3Client, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
  }

  /**
   * Verifies that 2500 keys are split into three batches (1000 + 1000 + 500) sent as
   * separate {@link DeleteObjectsRequest} calls.
   */
  @Test
  void deleteObjects_overBatchSize_splitsIntoMultipleRequests() {
    when(s3Buckets.getBucketName()).thenReturn(BUCKET_NAME);
    when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenReturn(DeleteObjectsResponse.builder().build());

    List<String> keys = IntStream.range(0, 2500)
        .mapToObj(i -> "key-" + i)
        .toList();

    s3Service.deleteObjects(keys);

    ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
    verify(s3Client, times(3)).deleteObjects(captor.capture());

    List<DeleteObjectsRequest> requests = captor.getAllValues();
    assertThat(requests.get(0).delete().objects()).hasSize(1000);
    assertThat(requests.get(1).delete().objects()).hasSize(1000);
    assertThat(requests.get(2).delete().objects()).hasSize(500);
  }

  /**
   * Verifies that when S3 returns an error for a deleted key, a {@link RuntimeException} is
   * thrown containing the key name and error message.
   */
  @Test
  void deleteObjects_whenResponseHasErrors_throwsRuntimeException() {
    when(s3Buckets.getBucketName()).thenReturn(BUCKET_NAME);
    S3Error error = S3Error.builder().key("bad.jpg").message("AccessDenied").build();
    DeleteObjectsResponse errorResponse = DeleteObjectsResponse.builder()
        .errors(error)
        .build();
    when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(errorResponse);

    assertThatThrownBy(() -> s3Service.deleteObjects(List.of("bad.jpg")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("bad.jpg")
        .hasMessageContaining("AccessDenied");
  }

  /**
   * Verifies that when S3 returns multiple errors, the thrown exception message includes
   * all failed keys and their respective error messages.
   */
  @Test
  void deleteObjects_whenResponseHasMultipleErrors_includesAllInMessage() {
    when(s3Buckets.getBucketName()).thenReturn(BUCKET_NAME);
    DeleteObjectsResponse errorResponse = DeleteObjectsResponse.builder()
        .errors(
            S3Error.builder().key("one.jpg").message("AccessDenied").build(),
            S3Error.builder().key("two.jpg").message("NoSuchKey").build())
        .build();
    when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(errorResponse);

    assertThatThrownBy(() -> s3Service.deleteObjects(List.of("one.jpg", "two.jpg")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("one.jpg: AccessDenied")
        .hasMessageContaining("two.jpg: NoSuchKey");
  }

  // ── collectS3Keys (static) ─────────────────────────────────

  /**
   * Verifies that an empty flashcard list produces an empty key list.
   */
  @Test
  void collectS3Keys_emptyList_returnsEmptyList() {
    List<String> result = S3Service.collectS3Keys(Collections.emptyList());
    assertThat(result).isEmpty();
  }

  /**
   * Verifies that a flashcard with both question and answer media returns both S3 keys
   * in order (question first, then answer).
   */
  @Test
  void collectS3Keys_cardWithBothMediaKeys_returnsBoth() {
    Flashcard fc = new Flashcard();
    fc.setQuestionMediaMetadata(mediaWithKey("q-key.jpg"));
    fc.setAnswerMediaMetadata(mediaWithKey("a-key.mp3"));

    List<String> result = S3Service.collectS3Keys(List.of(fc));

    assertThat(result).containsExactly("q-key.jpg", "a-key.mp3");
  }

  /**
   * Verifies that a flashcard with {@code null} media metadata on both sides produces
   * no keys (nulls are safely skipped).
   */
  @Test
  void collectS3Keys_cardWithNullMetadata_skipsNulls() {
    Flashcard fc = new Flashcard();
    fc.setQuestionMediaMetadata(null);
    fc.setAnswerMediaMetadata(null);

    List<String> result = S3Service.collectS3Keys(List.of(fc));

    assertThat(result).isEmpty();
  }

  /**
   * Verifies that a {@link MediaMetadata} with a {@code null} S3 key is excluded from
   * the result, while a valid key on the other side is still collected.
   */
  @Test
  void collectS3Keys_metadataWithNullKey_skipsIt() {
    Flashcard fc = new Flashcard();
    fc.setQuestionMediaMetadata(mediaWithKey(null));
    fc.setAnswerMediaMetadata(mediaWithKey("a-key.mp3"));

    List<String> result = S3Service.collectS3Keys(List.of(fc));

    assertThat(result).containsExactly("a-key.mp3");
  }

  /**
   * Verifies that keys from multiple flashcards are collected in card order, with
   * question keys before answer keys within each card, skipping null metadata.
   */
  @Test
  void collectS3Keys_multipleCards_collectsAllKeysInOrder() {
    Flashcard fc1 = new Flashcard();
    fc1.setQuestionMediaMetadata(mediaWithKey("q1.jpg"));
    fc1.setAnswerMediaMetadata(null);

    Flashcard fc2 = new Flashcard();
    fc2.setQuestionMediaMetadata(null);
    fc2.setAnswerMediaMetadata(mediaWithKey("a2.mp3"));

    Flashcard fc3 = new Flashcard();
    fc3.setQuestionMediaMetadata(mediaWithKey("q3.png"));
    fc3.setAnswerMediaMetadata(mediaWithKey("a3.wav"));

    List<String> result = S3Service.collectS3Keys(List.of(fc1, fc2, fc3));

    assertThat(result).containsExactly("q1.jpg", "a2.mp3", "q3.png", "a3.wav");
  }

  private MediaMetadata mediaWithKey(String key) {
    MediaMetadata m = new MediaMetadata();
    m.setS3Key(key);
    return m;
  }
}
