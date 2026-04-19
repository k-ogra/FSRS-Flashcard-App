package com.kogura.FSRS_Flashcard_App.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;

import com.kogura.FSRS_Flashcard_App.dto.PresignedPostResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.kogura.FSRS_Flashcard_App.config.S3BucketsConfig;
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
  private S3BucketsConfig s3Buckets;

  private S3Service s3Service;

  @BeforeEach
  void setUp() {
    s3Service = new S3Service(s3Buckets, s3Client);
  }

  @AfterEach
  void tearDown() {
    System.clearProperty("aws.accessKeyId");
    System.clearProperty("aws.secretAccessKey");
    System.clearProperty("aws.sessionToken");
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

  // ── escapeJson ─────────────────────────────────────────────

  /**
   * A plain string with no special characters must pass through unchanged.
   */
  @Test
  void escapeJson_plainString_returnsUnchanged() throws Exception {
    assertThat(invokeEscapeJson("hello world")).isEqualTo("hello world");
  }

  /**
   * An empty string must return an empty string.
   */
  @Test
  void escapeJson_emptyString_returnsEmpty() throws Exception {
    assertThat(invokeEscapeJson("")).isEqualTo("");
  }

  /**
   * A double-quote character must be escaped to {@code \"}.
   */
  @Test
  void escapeJson_doubleQuote_isEscaped() throws Exception {
    assertThat(invokeEscapeJson("\"")).isEqualTo("\\\"");
  }

  /**
   * A backslash must be escaped to {@code \\}.
   */
  @Test
  void escapeJson_backslash_isEscaped() throws Exception {
    assertThat(invokeEscapeJson("\\")).isEqualTo("\\\\");
  }

  /**
   * A backslash immediately followed by a double-quote must produce {@code \\\"} —
   * the backslash is doubled first, then the quote is escaped.
   */
  @Test
  void escapeJson_backslashFollowedByQuote_bothEscaped() throws Exception {
    assertThat(invokeEscapeJson("\\\"")).isEqualTo("\\\\\\\"");
  }

  /**
   * Multiple special characters scattered through the string must all be escaped in place.
   */
  @Test
  void escapeJson_mixedSpecialChars_allEscaped() throws Exception {
    assertThat(invokeEscapeJson("hello \"world\" and \\path"))
        .isEqualTo("hello \\\"world\\\" and \\\\path");
  }

  /**
   * Non-ASCII Unicode characters must pass through unchanged — only {@code \} and {@code "}
   * are escaped.
   */
  @Test
  void escapeJson_unicodeChars_passThrough() throws Exception {
    assertThat(invokeEscapeJson("café \u4e2d\u6587")).isEqualTo("café \u4e2d\u6587");
  }

  // ── hexEncode ──────────────────────────────────────────────

  /**
   * An empty byte array must produce an empty string.
   */
  @Test
  void hexEncode_emptyArray_returnsEmptyString() throws Exception {
    assertThat(invokeHexEncode(new byte[0])).isEqualTo("");
  }

  /**
   * A single zero byte must produce {@code "00"} with the leading zero preserved.
   */
  @Test
  void hexEncode_singleZeroByte_returnsTwoZeros() throws Exception {
    assertThat(invokeHexEncode(new byte[]{0x00})).isEqualTo("00");
  }

  /**
   * A single byte with value 0x0F must produce {@code "0f"} — leading zero not dropped.
   */
  @Test
  void hexEncode_singleNibbleByte_returnsLeadingZero() throws Exception {
    assertThat(invokeHexEncode(new byte[]{0x0F})).isEqualTo("0f");
  }

  /**
   * A single byte with value 0xFF must produce lowercase {@code "ff"}, not uppercase {@code "FF"}.
   */
  @Test
  void hexEncode_maxByte_returnsLowercaseFF() throws Exception {
    assertThat(invokeHexEncode(new byte[]{(byte) 0xFF})).isEqualTo("ff");
  }

  /**
   * The ASCII bytes for {@code "Hello"} must encode to the known hex string {@code "48656c6c6f"}.
   */
  @Test
  void hexEncode_knownAsciiBytes_returnsCorrectHex() throws Exception {
    byte[] bytes = "Hello".getBytes(StandardCharsets.US_ASCII);
    assertThat(invokeHexEncode(bytes)).isEqualTo("48656c6c6f");
  }

  /**
   * Output must always be lowercase — no uppercase hex digits permitted.
   */
  @Test
  void hexEncode_output_isAlwaysLowercase() throws Exception {
    byte[] allBytes = new byte[256];
    for (int i = 0; i < 256; i++) allBytes[i] = (byte) i;
    assertThat(invokeHexEncode(allBytes)).doesNotContainPattern("[A-F]");
  }

  /**
   * The output length must always equal {@code bytes.length * 2}.
   */
  @Test
  void hexEncode_outputLength_isTwiceInputLength() throws Exception {
    byte[] bytes = new byte[]{0x01, 0x23, 0x45, 0x67, (byte) 0x89};
    assertThat(invokeHexEncode(bytes)).hasSize(bytes.length * 2);
  }

  // ── createPresignedPostData ────────────────────────────────

  /**
   * The response must be non-null and carry a non-null {@code url} and {@code fields} map.
   */
  @Test
  void createPresignedPostData_returnsNonNullResponse() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of("flashcardid", "1"), "image/");
    assertThat(result).isNotNull();
    assertThat(result.getUrl()).isNotNull();
    assertThat(result.getFields()).isNotNull();
  }

  /**
   * The upload URL must be of the form {@code https://{bucket}.s3.{region}.amazonaws.com/},
   * embedding both the bucket name and the configured region.
   */
  @Test
  void createPresignedPostData_urlContainsBucketNameAndRegion() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    assertThat(result.getUrl()).contains(BUCKET_NAME).contains("us-east-1").endsWith("/");
  }

  /**
   * The {@code fields} map must contain all eight standard POST policy form fields.
   */
  @Test
  void createPresignedPostData_fieldsContainAllRequiredKeys() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    assertThat(result.getFields()).containsKeys(
            "key", "Content-Type", "x-amz-algorithm", "x-amz-credential",
            "x-amz-date", "Policy", "x-amz-signature", "success_action_status");
  }

  /**
   * The {@code key} form field must equal the key argument passed to the method.
   */
  @Test
  void createPresignedPostData_keyFieldMatchesInputKey() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    assertThat(result.getFields().get("key")).isEqualTo("uploads/alice/uuid/photo.jpg");
  }

  /**
   * The {@code Content-Type} form field must equal the {@code contentTypePrefix} argument.
   */
  @Test
  void createPresignedPostData_contentTypeFieldMatchesPrefix() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/audio.mp3", Map.of(), "audio/");
    assertThat(result.getFields().get("Content-Type")).isEqualTo("audio/");
  }

  /**
   * The {@code x-amz-algorithm} field must be {@code AWS4-HMAC-SHA256}.
   */
  @Test
  void createPresignedPostData_algorithmIsAwsSigV4() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    assertThat(result.getFields().get("x-amz-algorithm")).isEqualTo("AWS4-HMAC-SHA256");
  }

  /**
   * The {@code x-amz-credential} field must start with the access key ID and contain
   * the region and service scope ({@code /us-east-1/s3/aws4_request}).
   */
  @Test
  void createPresignedPostData_credentialContainsAccessKeyAndRegionScope() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    String credential = result.getFields().get("x-amz-credential");
    assertThat(credential)
            .startsWith("AKIAIOSFODNN7EXAMPLE/")
            .contains("/us-east-1/s3/aws4_request");
  }

  /**
   * The {@code x-amz-date} field must match the SigV4 timestamp format {@code yyyyMMdd'T'HHmmss'Z'}.
   */
  @Test
  void createPresignedPostData_xAmzDateMatchesTimestampFormat() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    assertThat(result.getFields().get("x-amz-date")).matches("\\d{8}T\\d{6}Z");
  }

  /**
   * The {@code x-amz-signature} field must be a 64-character lowercase hex string — the
   * expected output of HMAC-SHA256 (32 bytes × 2 hex digits per byte).
   */
  @Test
  void createPresignedPostData_signatureIs64CharLowercaseHex() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    assertThat(result.getFields().get("x-amz-signature"))
            .hasSize(64)
            .matches("[0-9a-f]+");
  }

  /**
   * The {@code Policy} field must be a valid Base64-encoded string (i.e. decodeable without error).
   */
  @Test
  void createPresignedPostData_policyFieldIsValidBase64() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    assertThatCode(() -> Base64.getDecoder().decode(result.getFields().get("Policy")))
            .doesNotThrowAnyException();
  }

  /**
   * The {@code success_action_status} field must be {@code "204"} so S3 returns HTTP 204
   * on a successful upload rather than a redirect.
   */
  @Test
  void createPresignedPostData_successActionStatusIs204() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    assertThat(result.getFields().get("success_action_status")).isEqualTo("204");
  }

  /**
   * Each metadata entry must appear in {@code fields} as {@code x-amz-meta-{key}} with
   * the original value preserved.
   */
  @Test
  void createPresignedPostData_metadataAppearsAsXAmzMetaFields() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg",
            Map.of("flashcardid", "42", "isquestion", "true"),
            "image/");
    assertThat(result.getFields())
            .containsEntry("x-amz-meta-flashcardid", "42")
            .containsEntry("x-amz-meta-isquestion", "true");
  }

  /**
   * When no metadata is supplied, no {@code x-amz-meta-*} keys must appear in {@code fields}.
   */
  @Test
  void createPresignedPostData_emptyMetadata_noXAmzMetaFields() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    assertThat(result.getFields().keySet()).noneMatch(k -> k.startsWith("x-amz-meta-"));
  }

  /**
   * When the resolved credentials are basic (no session token), the
   * {@code x-amz-security-token} field must be absent from the response.
   */
  @Test
  void createPresignedPostData_basicCredentials_noSecurityTokenField() {
    setUpPresignedPostData();
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    assertThat(result.getFields()).doesNotContainKey("x-amz-security-token");
  }

  /**
   * When a session token is present in the resolved credentials, the
   * {@code x-amz-security-token} field must appear in {@code fields} with the token value.
   */
  @Test
  void createPresignedPostData_sessionCredentials_includesSecurityTokenField() {
    setUpPresignedPostData();
    System.setProperty("aws.sessionToken", "AQoDYXdzEJr-testSessionToken");
    PresignedPostResponse result = s3Service.createPresignedPostData(
            "uploads/alice/uuid/photo.jpg", Map.of(), "image/");
    assertThat(result.getFields())
            .containsEntry("x-amz-security-token", "AQoDYXdzEJr-testSessionToken");
  }

  // ── createPresignedPostData helper ─────────────────────────

  private void setUpPresignedPostData() {
    ReflectionTestUtils.setField(s3Service, "awsRegion", "us-east-1");
    when(s3Buckets.getBucketName()).thenReturn(BUCKET_NAME);
    System.setProperty("aws.accessKeyId", "AKIAIOSFODNN7EXAMPLE");
    System.setProperty("aws.secretAccessKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
  }

  // ── Reflection helpers ─────────────────────────────────────

  private String invokeEscapeJson(String value) throws Exception {
    Method method = S3Service.class.getDeclaredMethod("escapeJson", String.class);
    method.setAccessible(true);
    return (String) method.invoke(s3Service, value);
  }

  private String invokeHexEncode(byte[] bytes) throws Exception {
    Method method = S3Service.class.getDeclaredMethod("hexEncode", byte[].class);
    method.setAccessible(true);
    return (String) method.invoke(s3Service, bytes);
  }
}
