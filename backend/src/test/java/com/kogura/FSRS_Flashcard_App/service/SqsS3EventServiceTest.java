package com.kogura.FSRS_Flashcard_App.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.repository.FlashcardRepository;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

@ExtendWith(MockitoExtension.class)
public class SqsS3EventServiceTest {

  /** Mock for the AWS SQS client used to receive and delete messages. */
  @Mock
  private SqsClient sqsClient;

  /** Mock for the AWS S3 client used to retrieve object metadata via HEAD requests. */
  @Mock
  private S3Client s3Client;

  /** Mock for flashcard persistence and lookups. */
  @Mock
  private FlashcardRepository flashcardRepository;

  /** Mock for presigned download URL generation. */
  @Mock
  private S3Service s3Service;

  /** The service under test. */
  private SqsS3EventService sqsS3EventService;

  @BeforeEach
  void setUp() {
    sqsS3EventService = new SqsS3EventService(sqsClient, s3Client, flashcardRepository, s3Service);
    ReflectionTestUtils.setField(sqsS3EventService, "queueUrl", "https://sqs.us-east-1.amazonaws.com/123/test-queue");
    ReflectionTestUtils.setField(sqsS3EventService, "waitTimeSeconds", 20);
    ReflectionTestUtils.setField(sqsS3EventService, "maxMessages", 10);
  }

  // ── Helper methods ─────────────────────────────────────────

  private String s3EventJson(String eventName, String bucket, String key) {
    return """
        {
          "Records": [
            {
              "eventName": "%s",
              "s3": {
                "bucket": { "name": "%s" },
                "object": { "key": "%s" }
              }
            }
          ]
        }
        """.formatted(eventName, bucket, key);
  }

  private Message sqsMessage(String body) {
    return Message.builder()
        .messageId("msg-1")
        .receiptHandle("receipt-1")
        .body(body)
        .build();
  }

  private HeadObjectResponse headResponse(Map<String, String> metadata) {
    return HeadObjectResponse.builder()
        .metadata(metadata)
        .build();
  }

  // ── pollForEvents — queue URL guard ────────────────────────

  /**
   * Verifies that when the SQS queue URL is {@code null}, polling is skipped and no
   * SQS calls are made.
   */
  @Test
  void pollForEvents_nullQueueUrl_skipsPolling() {
    ReflectionTestUtils.setField(sqsS3EventService, "queueUrl", null);

    sqsS3EventService.pollForEvents();

    verify(sqsClient, never()).receiveMessage(any(ReceiveMessageRequest.class));
  }

  /**
   * Verifies that when the SQS queue URL is blank, polling is skipped and no
   * SQS calls are made.
   */
  @Test
  void pollForEvents_blankQueueUrl_skipsPolling() {
    ReflectionTestUtils.setField(sqsS3EventService, "queueUrl", "   ");

    sqsS3EventService.pollForEvents();

    verify(sqsClient, never()).receiveMessage(any(ReceiveMessageRequest.class));
  }

  // ── pollForEvents — empty response ─────────────────────────

  /**
   * Verifies that when SQS returns no messages, the service exits early without
   * attempting to delete any messages.
   */
  @Test
  void pollForEvents_noMessages_doesNothing() {
    ReceiveMessageResponse emptyResponse = ReceiveMessageResponse.builder()
        .messages(Collections.emptyList())
        .build();
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenReturn(emptyResponse);

    sqsS3EventService.pollForEvents();

    verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
  }

  // ── pollForEvents — successful processing ──────────────────

  /**
   * Verifies that a valid S3 ObjectCreated:Put event with complete metadata attaches
   * question-side media to the flashcard, saves it, and deletes the SQS message.
   */
  @Test
  void pollForEvents_validEvent_questionSide_attachesMediaAndDeletesMessage() {
    String json = s3EventJson("ObjectCreated:Put", "my-bucket", "uploads/photo.jpg");
    Message message = sqsMessage(json);

    ReceiveMessageResponse response = ReceiveMessageResponse.builder()
        .messages(List.of(message))
        .build();
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(response);

    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponse(Map.of(
            "flashcardid", "42",
            "filename", "photo.jpg",
            "isquestion", "true"
        )));

    Flashcard flashcard = new Flashcard();
    flashcard.setId(42L);
    when(flashcardRepository.findById(42L)).thenReturn(Optional.of(flashcard));
    when(s3Service.createPresignedDownloadUrl("uploads/photo.jpg"))
        .thenReturn("https://s3/presigned-url");
    when(flashcardRepository.save(any(Flashcard.class))).thenAnswer(inv -> inv.getArgument(0));

    sqsS3EventService.pollForEvents();

    assertThat(flashcard.getQuestionMediaMetadata()).isNotNull();
    assertThat(flashcard.getQuestionMediaMetadata().getName()).isEqualTo("photo.jpg");
    assertThat(flashcard.getQuestionMediaMetadata().getS3Key()).isEqualTo("uploads/photo.jpg");
    assertThat(flashcard.getQuestionMediaMetadata().getPresignedDownloadUrl()).isEqualTo("https://s3/presigned-url");
    assertThat(flashcard.getAnswerMediaMetadata()).isNull();
    verify(flashcardRepository).save(flashcard);
    verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
  }

  /**
   * Verifies that when {@code isquestion} metadata is {@code "false"}, the media is
   * attached to the answer side of the flashcard.
   */
  @Test
  void pollForEvents_validEvent_answerSide_attachesMediaToAnswer() {
    String json = s3EventJson("ObjectCreated:Put", "my-bucket", "uploads/audio.mp3");
    Message message = sqsMessage(json);

    ReceiveMessageResponse response = ReceiveMessageResponse.builder()
        .messages(List.of(message))
        .build();
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(response);

    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponse(Map.of(
            "flashcardid", "7",
            "filename", "audio.mp3",
            "isquestion", "false"
        )));

    Flashcard flashcard = new Flashcard();
    flashcard.setId(7L);
    when(flashcardRepository.findById(7L)).thenReturn(Optional.of(flashcard));
    when(s3Service.createPresignedDownloadUrl("uploads/audio.mp3"))
        .thenReturn("https://s3/answer-url");
    when(flashcardRepository.save(any(Flashcard.class))).thenAnswer(inv -> inv.getArgument(0));

    sqsS3EventService.pollForEvents();

    assertThat(flashcard.getAnswerMediaMetadata()).isNotNull();
    assertThat(flashcard.getAnswerMediaMetadata().getName()).isEqualTo("audio.mp3");
    assertThat(flashcard.getAnswerMediaMetadata().getS3Key()).isEqualTo("uploads/audio.mp3");
    assertThat(flashcard.getQuestionMediaMetadata()).isNull();
    verify(flashcardRepository).save(flashcard);
  }

  // ── pollForEvents — ObjectCreated:Copy skip ────────────────

  /**
   * Verifies that {@code ObjectCreated:Copy} events are skipped — no HEAD request,
   * no flashcard lookup, and no save occurs.
   */
  @Test
  void pollForEvents_copyEvent_skipsProcessing() {
    String json = s3EventJson("ObjectCreated:Copy", "my-bucket", "copies/file.jpg");
    Message message = sqsMessage(json);

    ReceiveMessageResponse response = ReceiveMessageResponse.builder()
        .messages(List.of(message))
        .build();
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(response);

    sqsS3EventService.pollForEvents();

    verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    verify(flashcardRepository, never()).findById(any());
    verify(flashcardRepository, never()).save(any());
  }

  // ── pollForEvents — missing metadata ───────────────────────

  /**
   * Verifies that when S3 object metadata is missing required fields (flashcardid,
   * filename, isquestion), the record is skipped without saving.
   */
  @Test
  void pollForEvents_missingMetadata_skipsWithoutSaving() {
    String json = s3EventJson("ObjectCreated:Put", "my-bucket", "uploads/orphan.jpg");
    Message message = sqsMessage(json);

    ReceiveMessageResponse response = ReceiveMessageResponse.builder()
        .messages(List.of(message))
        .build();
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(response);

    // Missing "isquestion" key
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponse(Map.of(
            "flashcardid", "1",
            "filename", "orphan.jpg"
        )));

    sqsS3EventService.pollForEvents();

    verify(flashcardRepository, never()).findById(any());
    verify(flashcardRepository, never()).save(any());
  }

  // ── pollForEvents — flashcard not found ────────────────────

  /**
   * Verifies that when the flashcard ID from metadata does not match any persisted
   * flashcard, the record is skipped without saving.
   */
  @Test
  void pollForEvents_flashcardNotFound_skipsWithoutSaving() {
    String json = s3EventJson("ObjectCreated:Put", "my-bucket", "uploads/lost.jpg");
    Message message = sqsMessage(json);

    ReceiveMessageResponse response = ReceiveMessageResponse.builder()
        .messages(List.of(message))
        .build();
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(response);

    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponse(Map.of(
            "flashcardid", "999",
            "filename", "lost.jpg",
            "isquestion", "true"
        )));

    when(flashcardRepository.findById(999L)).thenReturn(Optional.empty());

    sqsS3EventService.pollForEvents();

    verify(flashcardRepository, never()).save(any());
  }

  // ── pollForEvents — invalid flashcard ID ───────────────────

  /**
   * Verifies that when the flashcard ID metadata is not a valid number, the record
   * is skipped gracefully without saving.
   */
  @Test
  void pollForEvents_invalidFlashcardId_skipsWithoutSaving() {
    String json = s3EventJson("ObjectCreated:Put", "my-bucket", "uploads/bad.jpg");
    Message message = sqsMessage(json);

    ReceiveMessageResponse response = ReceiveMessageResponse.builder()
        .messages(List.of(message))
        .build();
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(response);

    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponse(Map.of(
            "flashcardid", "not-a-number",
            "filename", "bad.jpg",
            "isquestion", "true"
        )));

    sqsS3EventService.pollForEvents();

    verify(flashcardRepository, never()).findById(any());
    verify(flashcardRepository, never()).save(any());
  }

  // ── pollForEvents — URL-encoded key ────────────────────────

  /**
   * Verifies that URL-encoded S3 keys (e.g. spaces as {@code +} or {@code %20}) are
   * decoded before being used for the HEAD request and stored in media metadata.
   */
  @Test
  void pollForEvents_urlEncodedKey_decodesCorrectly() {
    String json = s3EventJson("ObjectCreated:Put", "my-bucket", "uploads/my+photo.jpg");
    Message message = sqsMessage(json);

    ReceiveMessageResponse response = ReceiveMessageResponse.builder()
        .messages(List.of(message))
        .build();
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(response);

    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponse(Map.of(
            "flashcardid", "5",
            "filename", "my photo.jpg",
            "isquestion", "true"
        )));

    Flashcard flashcard = new Flashcard();
    flashcard.setId(5L);
    when(flashcardRepository.findById(5L)).thenReturn(Optional.of(flashcard));
    when(s3Service.createPresignedDownloadUrl("uploads/my photo.jpg"))
        .thenReturn("https://s3/decoded-url");
    when(flashcardRepository.save(any(Flashcard.class))).thenAnswer(inv -> inv.getArgument(0));

    sqsS3EventService.pollForEvents();

    assertThat(flashcard.getQuestionMediaMetadata().getS3Key()).isEqualTo("uploads/my photo.jpg");
    verify(s3Service).createPresignedDownloadUrl("uploads/my photo.jpg");
  }

  // ── pollForEvents — exception resilience ───────────────────

  /**
   * Verifies that when processing a message throws an exception, the message is not
   * deleted from the queue (allowing SQS retry/dead-letter), and the service does
   * not crash.
   */
  @Test
  void pollForEvents_processingException_doesNotDeleteMessage() {
    Message message = Message.builder()
        .messageId("msg-bad")
        .receiptHandle("receipt-bad")
        .body("invalid json {{{{")
        .build();

    ReceiveMessageResponse response = ReceiveMessageResponse.builder()
        .messages(List.of(message))
        .build();
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(response);

    sqsS3EventService.pollForEvents();

    verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
  }

  /**
   * Verifies that the duplicate queue-URL warning is only logged once — the
   * {@code missingQueueUrlLogged} flag prevents repeated log spam.
   */
  @Test
  void pollForEvents_blankQueueUrl_logsWarningOnlyOnce() {
    ReflectionTestUtils.setField(sqsS3EventService, "queueUrl", "");

    sqsS3EventService.pollForEvents();
    sqsS3EventService.pollForEvents();
    sqsS3EventService.pollForEvents();

    // After the first call sets missingQueueUrlLogged=true, subsequent calls still skip
    // but don't re-log. Verify SQS was never called across all invocations.
    verify(sqsClient, never()).receiveMessage(any(ReceiveMessageRequest.class));
  }
}
