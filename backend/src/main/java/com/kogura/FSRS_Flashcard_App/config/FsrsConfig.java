package com.kogura.FSRS_Flashcard_App.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.openspacedrepetition.Scheduler;


/*
 * Configuration for the FSRS scheduler.
 * Lets spring inject the scheduler bean into the StudyService.
 */
@Configuration
public class FsrsConfig {

  /*
   * Create a new FSRS scheduler bean.
   * This bean is injected into the StudyService.
   * @return The FSRS scheduler.
   */
  @Bean
  public Scheduler fsrsScheduler() {
    return Scheduler.builder().build();
  }
}
