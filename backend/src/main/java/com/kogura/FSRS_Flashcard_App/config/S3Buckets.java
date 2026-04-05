package com.kogura.FSRS_Flashcard_App.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;


@Configuration
@ConfigurationProperties(prefix = "aws.s3.bucket")
public class S3Buckets {
  
  @Value("${aws.s3.bucket.name}")
  private String bucketName;

  public String getBucketName() {
      return this.bucketName;
  }

}
