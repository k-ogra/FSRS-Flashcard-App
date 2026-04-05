package com.kogura.FSRS_Flashcard_App.service;


import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.model.MediaMetadata;
import com.kogura.FSRS_Flashcard_App.repository.FlashcardRepository;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.eventnotifications.s3.model.S3EventNotification;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

@Service
public class SqsS3EventService {
  private static final Logger log = LoggerFactory.getLogger(SqsS3EventService.class);

  private final SqsClient sqsClient;
  private final S3Client s3Client;
  private final FlashcardRepository flashcardRepository;
  private final S3Service s3Service;

  // Event.S3_OBJECT_CREATED_COPY doesn't include the "s3:" prefix, so use custom string
  private static final String S3_EVENT_COPY = "ObjectCreated:Copy";

  @Value("${aws.sqs.queueUrl}")
  private String queueUrl;

  @Value("${aws.sqs.waitTimeSeconds:20}")
  private int waitTimeSeconds;

  @Value("${aws.sqs.maxMessages:10}")
  private int maxMessages;

  private boolean missingQueueUrlLogged = false;

  public SqsS3EventService(SqsClient sqsClient, S3Client s3Client, 
                            FlashcardRepository flashcardRepository,
                            S3Service s3Service) {
    this.sqsClient = sqsClient;
    this.s3Client = s3Client;
    this.flashcardRepository = flashcardRepository;
    this.s3Service = s3Service;
  }

  @Scheduled(fixedDelayString = "${aws.sqs.pollDelayMs:5000}")
  public void pollForEvents() {
    log.info("POLLING FOR EVENTS");
    if (this.queueUrl == null || this.queueUrl.isBlank()) {
      if (!this.missingQueueUrlLogged) {
        log.warn("SQS queueUrl not configured; skipping polling");
        this.missingQueueUrlLogged = true;
      }
      return;
    }

    ReceiveMessageRequest request = ReceiveMessageRequest.builder()
        .queueUrl(this.queueUrl)
        .waitTimeSeconds(this.waitTimeSeconds)
        .maxNumberOfMessages(this.maxMessages)
        .build();

    ReceiveMessageResponse response = sqsClient.receiveMessage(request);
    if (response.messages().isEmpty()) {
      return;
    }

    for (Message message : response.messages()) {
      try {
        handleMessage(message);
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
            .queueUrl(this.queueUrl)
            .receiptHandle(message.receiptHandle())
            .build());
        log.info("Message deleted");
      } catch (Exception ex) {
        log.error("Failed to process SQS message {}", message.messageId(), ex);
      }
    }
  }

  private void handleMessage(Message message) throws Exception {
    // log.info("Message body is {}", message.body());
    System.out.println(message.body());
    S3EventNotification event = S3EventNotification.fromJson(message.body());
    // Log the S3 event notification record details.
    if (event.getRecords() != null) {
      log.info("Event records are not null");
      event.getRecords().forEach(record -> {
          String key = record.getS3().getObject().getKey();
          // getEventName doesn't include the "s3:" prefix, so add it back
          if (record.getEventName().equals(S3_EVENT_COPY)) {
            return; // Skip ObjectCreated:Copy events
          }
          String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8);
          log.info(record.toString());
          log.info("Bucket name is {} and decoded key is {}", record.getS3().getBucket().getName(), decodedKey);

          HeadObjectResponse head = this.s3Client.headObject(HeadObjectRequest.builder()
          .bucket(record.getS3().getBucket().getName())
          .key(decodedKey)
          .build());
      
          Map<String, String> metadata = head.metadata(); // keys are lowercase
          String flashcardIdStr = metadata.get("flashcardid");
          String fileName = metadata.get("filename");
          String isQuestionStr = metadata.get("isquestion");
          
          log.info("Flashcard ID is {}", flashcardIdStr);
          log.info("File name is {}", fileName);
          log.info("Is question side: {}", isQuestionStr);

          if (flashcardIdStr == null || fileName == null || isQuestionStr == null) {
            log.error("Missing required metadata in S3 object");
            return;
          }

          try {
            Long flashcardId = Long.parseLong(flashcardIdStr);
            boolean isQuestion = Boolean.parseBoolean(isQuestionStr);

            Optional<Flashcard> optionalFlashcard = flashcardRepository.findById(flashcardId);
            if (optionalFlashcard.isEmpty()) {
              log.error("Flashcard with ID {} not found", flashcardId);
              return;
            }

            Flashcard flashcard = optionalFlashcard.get();

            // Generate presigned download URL
            String presignedDownloadUrl = s3Service.createPresignedDownloadUrl(decodedKey);
            Instant urlExpiresAt = Instant.now().plusSeconds(600); // 10 minutes (600 seconds)
            
            log.info("Generated presigned download URL, expires at: {}", urlExpiresAt);

            // Create new MediaMetadata entry
            MediaMetadata newMediaMetadata = new MediaMetadata();
            newMediaMetadata.setName(fileName);
            newMediaMetadata.setS3Key(decodedKey);
            newMediaMetadata.setPresignedDownloadUrl(presignedDownloadUrl);
            newMediaMetadata.setUrlExpiresAt(urlExpiresAt);

            // Associate with the correct side of the flashcard
            if (isQuestion) {
              flashcard.setQuestionMediaMetadata(newMediaMetadata);
              log.info("Associated media with question side of flashcard {}", flashcardId);
            } else {
              flashcard.setAnswerMediaMetadata(newMediaMetadata);
              log.info("Associated media with answer side of flashcard {}", flashcardId);
            }

            // Save the flashcard (cascade will save the MediaMetadata)
            flashcardRepository.save(flashcard);
            log.info("Successfully saved flashcard {} with media metadata and presigned URL", flashcardId);

          } catch (NumberFormatException e) {
            log.error("Invalid flashcard ID format: {}", flashcardIdStr, e);
          } catch (Exception e) {
            log.error("Error processing media metadata for flashcard", e);
          }
      });
    }
  }
}
