package com.kogura.FSRS_Flashcard_App.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.kogura.FSRS_Flashcard_App.config.S3Buckets;
import com.kogura.FSRS_Flashcard_App.dto.PresignedPostResponse;
import com.kogura.FSRS_Flashcard_App.model.Flashcard;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * Service for interacting with Amazon S3 — handles object copies, deletions,
 * presigned download URLs, and presigned POST policies for form-based uploads.
 */
@Service
public class S3Service {

  /** Maximum allowed upload file size in bytes (10 MB). */
  private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;

  /** The TTL for presigned URLs in seconds (10 minutes). */
  private static final int PRESIGNED_URL_TTL_SECONDS = 600;

  /** Provides the configured S3 bucket name. */
  private final S3Buckets s3Buckets;

  /** AWS S3 client used for non-presigned operations (copy, delete). */
  private final S3Client s3Client;

  /** AWS region injected from application properties, used for SigV4 signing. */
  @Value("${aws.s3.region}")
  private String awsRegion;

  /**
   * Constructs the S3 service with required dependencies.
   *
   * @param s3Buckets provides the configured bucket name
   * @param s3Client  AWS S3 client for object operations
   */
  public S3Service(S3Buckets s3Buckets, S3Client s3Client) {
    this.s3Buckets = s3Buckets;
    this.s3Client = s3Client;
  }

  /**
   * Copies an object within the same bucket, replacing its metadata with an empty map.
   *
   * @param sourceKey      the S3 key of the source object
   * @param destinationKey the S3 key for the copied object
   */
  public void copyObject(String sourceKey, String destinationKey) {
    CopyObjectRequest request = CopyObjectRequest.builder()
        .sourceBucket(s3Buckets.getBucketName())
        .sourceKey(sourceKey)
        .destinationBucket(s3Buckets.getBucketName())
        .destinationKey(destinationKey)
        .metadataDirective(MetadataDirective.REPLACE)
        .metadata(java.util.Collections.emptyMap())
        .build();
    s3Client.copyObject(request);
  }

  /**
   * Issues a HEAD request for an S3 object and returns its user-defined metadata map.
   * Keys in the returned map are lowercase. Throws the underlying AWS SDK exception
   * if the object does not exist or is inaccessible.
   *
   * @param s3ObjectKey the S3 key of the object to HEAD
   * @return the user-defined metadata map (may be empty, never {@code null})
   */
  public Map<String, String> getObjectMetadata(String s3ObjectKey) {
    HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
        .bucket(s3Buckets.getBucketName())
        .key(s3ObjectKey)
        .build());
    return head.metadata();
  }

  /**
   * Creates a presigned GET URL for downloading an S3 object. The URL is valid for PRESIGNED_URL_TTL_SECONDS.
   *
   * @param s3Key the S3 key of the object to download
   * @return a presigned URL string that can be used in a GET request
   */
  public String createPresignedDownloadUrl(String s3Key) {
    try (S3Presigner presigner = S3Presigner.create()) {

        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(this.s3Buckets.getBucketName())
                .key(s3Key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(PRESIGNED_URL_TTL_SECONDS))
                .getObjectRequest(objectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toExternalForm();
    }
  }

  /**
   *  Create a presigned POST policy for form-based upload with file size and content-type enforcement. 
   *  The Java S3 SDK doesn't seem to have a way to create a presigned POST policy with content-type enforcement, so to do it manually
   *  @param key The S3 key for the uploaded file.
   *  @param metadata The metadata for the uploaded file.
   *  @param contentTypePrefix The content type prefix for the uploaded file.
   *  @return The presigned POST policy.
   */
  public PresignedPostResponse createPresignedPostData(String key, Map<String, String> metadata, String contentTypePrefix) {
    try {
      String bucketName = this.s3Buckets.getBucketName();

      // Resolve AWS credentials for SigV4 signing
      AwsCredentials credentials = DefaultCredentialsProvider.builder().build().resolveCredentials();
      String accessKeyId = credentials.accessKeyId();
      String secretAccessKey = credentials.secretAccessKey();
      String sessionToken = (credentials instanceof AwsSessionCredentials sessionCreds)
          ? sessionCreds.sessionToken() : null;

      // Timestamps
      Instant now = Instant.now();
      String dateStamp = DateTimeFormatter.ofPattern("yyyyMMdd")
          .withZone(ZoneOffset.UTC).format(now);
      String dateTimeStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
          .withZone(ZoneOffset.UTC).format(now);
      String expiration = DateTimeFormatter.ISO_INSTANT.format(now.plusSeconds(600));
      String credential = accessKeyId + "/" + dateStamp + "/" + awsRegion + "/s3/aws4_request";

      // Build the policy JSON
      StringBuilder sb = new StringBuilder();
      sb.append("{\"expiration\":\"").append(expiration).append("\",\"conditions\":[");
      sb.append("{\"bucket\":\"").append(bucketName).append("\"},");
      sb.append("{\"key\":\"").append(escapeJson(key)).append("\"},");
      sb.append("[\"starts-with\",\"$Content-Type\",\"").append(contentTypePrefix).append("\"],");
      sb.append("[\"content-length-range\",1,").append(MAX_FILE_SIZE).append("],");
      sb.append("{\"x-amz-algorithm\":\"AWS4-HMAC-SHA256\"},");
      sb.append("{\"x-amz-credential\":\"").append(credential).append("\"},");
      sb.append("{\"x-amz-date\":\"").append(dateTimeStamp).append("\"},");
      sb.append("{\"success_action_status\":\"204\"}");

      // Add metadata conditions
      for (Map.Entry<String, String> entry : metadata.entrySet()) {
        sb.append(",{\"x-amz-meta-").append(entry.getKey()).append("\":\"")
            .append(escapeJson(entry.getValue())).append("\"}");
      }

      // Session token condition (if using IAM roles)
      if (sessionToken != null) {
        sb.append(",{\"x-amz-security-token\":\"").append(escapeJson(sessionToken)).append("\"}");
      }

      sb.append("]}");
      String policyJson = sb.toString();
      String base64Policy = Base64.getEncoder().encodeToString(policyJson.getBytes(StandardCharsets.UTF_8));

      // SigV4 HMAC signing chain
      byte[] dateKey = hmacSha256(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), dateStamp);
      byte[] dateRegionKey = hmacSha256(dateKey, awsRegion);
      byte[] dateRegionServiceKey = hmacSha256(dateRegionKey, "s3");
      byte[] signingKey = hmacSha256(dateRegionServiceKey, "aws4_request");
      String signature = hexEncode(hmacSha256(signingKey, base64Policy));

      // Build form fields map
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("key", key);
      fields.put("Content-Type", contentTypePrefix); // placeholder — frontend overrides with actual MIME type
      fields.put("x-amz-algorithm", "AWS4-HMAC-SHA256");
      fields.put("x-amz-credential", credential);
      fields.put("x-amz-date", dateTimeStamp);
      fields.put("Policy", base64Policy);
      fields.put("x-amz-signature", signature);
      fields.put("success_action_status", "204");

      // Add metadata fields
      for (Map.Entry<String, String> entry : metadata.entrySet()) {
        fields.put("x-amz-meta-" + entry.getKey(), entry.getValue());
      }

      // Session token field (if using IAM roles)
      if (sessionToken != null) {
        fields.put("x-amz-security-token", sessionToken);
      }

      // S3 bucket URL
      String url = "https://" + bucketName + ".s3." + awsRegion + ".amazonaws.com/";

      return new PresignedPostResponse(url, fields);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create presigned POST data", e);
    }
  }


  /**
   * Deletes a list of S3 objects in batches of up to 1000 keys (the S3 API limit per request).
   * No-ops safely when the key list is {@code null} or empty. Throws a {@link RuntimeException}
   * if S3 reports any deletion errors.
   *
   * @param s3Keys the list of S3 keys to delete; may be {@code null} or empty
   * @throws RuntimeException if any objects fail to delete
   */
  public void deleteObjects(List<String> s3Keys) {
    if (s3Keys == null || s3Keys.isEmpty()) return;

    int batchSize = 1000;
    for (int i = 0; i < s3Keys.size(); i += batchSize) {
      List<String> batch = s3Keys.subList(i, Math.min(i + batchSize, s3Keys.size()));

      List<ObjectIdentifier> identifiers = batch.stream()
          .map(key -> ObjectIdentifier.builder().key(key).build())
          .toList();

      DeleteObjectsRequest request = DeleteObjectsRequest.builder()
          .bucket(s3Buckets.getBucketName())
          .delete(Delete.builder().objects(identifiers).quiet(false).build())
          .build();

      DeleteObjectsResponse response = s3Client.deleteObjects(request);

      if (response.hasErrors() && !response.errors().isEmpty()) {
        String errorDetails = response.errors().stream()
            .map(e -> e.key() + ": " + e.message())
            .collect(Collectors.joining(", "));
        throw new RuntimeException("Failed to delete S3 objects: " + errorDetails);
      }
    }
  }

  /**
   * Deletes every S3 object whose key begins with the given prefix, paginating through
   * the bucket listing in batches. No-ops safely when the prefix is {@code null} or empty.
   *
   * @param prefix the S3 key prefix under which all objects should be deleted
   */
  public void deleteObjectsByPrefix(String prefix) {
    if (prefix == null || prefix.isEmpty()) return;

    String continuationToken = null;
    do {
      ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
          .bucket(s3Buckets.getBucketName())
          .prefix(prefix);
      if (continuationToken != null) {
        requestBuilder.continuationToken(continuationToken);
      }

      ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
      List<String> keys = response.contents().stream()
          .map(S3Object::key)
          .toList();
      deleteObjects(keys);

      continuationToken = Boolean.TRUE.equals(response.isTruncated())
          ? response.nextContinuationToken()
          : null;
    } while (continuationToken != null);
  }

  /**
   * Collects all non-null S3 keys from the question and answer media metadata of the given
   * flashcards. Keys are returned in card order, with question keys before answer keys
   * within each card.
   *
   * @param flashcards the flashcards to extract S3 keys from
   * @return a list of S3 keys; empty if no media is attached
   */
  public static List<String> collectS3Keys(List<Flashcard> flashcards) {
    List<String> keys = new ArrayList<>();
    for (Flashcard fc : flashcards) {
      if (fc.getQuestionMediaMetadata() != null && fc.getQuestionMediaMetadata().getS3Key() != null) {
        keys.add(fc.getQuestionMediaMetadata().getS3Key());
      }
      if (fc.getAnswerMediaMetadata() != null && fc.getAnswerMediaMetadata().getS3Key() != null) {
        keys.add(fc.getAnswerMediaMetadata().getS3Key());
      }
    }
    return keys;
  }

  /**
   * Escapes backslashes and double quotes for safe inclusion in a JSON string literal.
   *
   * @param value the raw string to escape
   * @return the JSON-escaped string
   */
  private String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /**
   * Computes an HMAC-SHA256 message authentication code.
   *
   * @param key  the secret key bytes
   * @param data the data to sign
   * @return the raw HMAC-SHA256 bytes
   * @throws Exception if the HMAC algorithm is unavailable
   */
  private byte[] hmacSha256(byte[] key, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Encodes a byte array as a lowercase hexadecimal string.
   *
   * @param bytes the bytes to encode
   * @return the hex-encoded string
   */
  private String hexEncode(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
