package com.kogura.FSRS_Flashcard_App.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.regions.Region;

@Configuration
public class S3Config {
  
  @Value("${aws.s3.region}")
  private String awsRegion;

  @Bean
  public S3Client s3Client() {
    return S3Client.builder().region(Region.of(awsRegion)).build();
  }

}
