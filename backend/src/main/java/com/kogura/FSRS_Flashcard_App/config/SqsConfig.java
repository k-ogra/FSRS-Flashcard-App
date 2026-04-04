package com.kogura.FSRS_Flashcard_App.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class SqsConfig {

  @Value("${aws.sqs.region}")
  private String awsRegion;

  @Bean
  public SqsClient sqsClient() {
    return SqsClient.builder().region(Region.of(awsRegion)).build();
  }
}
