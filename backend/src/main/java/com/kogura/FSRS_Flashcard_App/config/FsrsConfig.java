package com.kogura.FSRS_Flashcard_App.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.openspacedrepetition.Scheduler;

@Configuration
public class FsrsConfig {

  @Bean
  public Scheduler fsrsScheduler() {
    return Scheduler.builder().build();
  }
}
