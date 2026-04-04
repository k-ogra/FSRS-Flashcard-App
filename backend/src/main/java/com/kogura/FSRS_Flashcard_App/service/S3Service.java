package com.kogura.FSRS_Flashcard_App.service;

import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kogura.FSRS_Flashcard_App.config.S3Buckets;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class S3Service {

  private final S3Buckets s3Buckets;
  private final S3Client s3Client;

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
        .metadataDirective(MetadataDirective.COPY)  
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
                .signatureDuration(Duration.ofMinutes(10))  // The URL will expire in 10 minutes.
                .getObjectRequest(objectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        System.out.println("Presigned URL: [{}]" + presignedRequest.url().toString());
        System.out.println("HTTP method: [{}]" + presignedRequest.httpRequest().method());

        return presignedRequest.url().toExternalForm();
    }
    }


  /* Create a presigned URL to use in a subsequent PUT request */
  public String createPresignedUploadUrl(String fileId, Map<String, String> metadata) {
    try (S3Presigner presigner = S3Presigner.create()) {

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(this.s3Buckets.getBucketName())
                .key(fileId)
                .metadata(metadata)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))  // The URL expires in 10 minutes.
                .putObjectRequest(objectRequest)
                .build();


        PresignedPutObjectRequest presignedRequest = presigner.presignPutObject(presignRequest);
        String myURL = presignedRequest.url().toString();
        System.out.println("Presigned URL to upload a file to: [{}]" + myURL);
        System.out.println("HTTP method: [{}]" + presignedRequest.httpRequest().method());

        return presignedRequest.url().toExternalForm();
    }
  }
}
