package com.kogura.FSRS_Flashcard_App.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.kogura.FSRS_Flashcard_App.config.S3Buckets;
import com.kogura.FSRS_Flashcard_App.dto.PresignedPostResponse;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@Service
public class S3Service {

  private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

  private final S3Buckets s3Buckets;
  private final S3Client s3Client;

  @Value("${aws.s3.region}")
  private String awsRegion;

  public S3Service(S3Buckets s3Buckets, S3Client s3Client) {
    this.s3Buckets = s3Buckets;
    this.s3Client = s3Client;
  }

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

  /* Create a pre-signed URL to download an object in a subsequent GET request. */
  public String createPresignedDownloadUrl(String s3Key) {
    try (S3Presigner presigner = S3Presigner.create()) {

        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(this.s3Buckets.getBucketName())
                .key(s3Key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(objectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toExternalForm();
    }
  }

  /**
   *  Create a presigned POST policy for form-based upload with file size and content-type enforcement. 
   *  S3 SDK doesn't seem to have a way to create a presigned POST policy with content-type enforcement, so to do it manually
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

  private String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private byte[] hmacSha256(byte[] key, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
  }

  private String hexEncode(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
