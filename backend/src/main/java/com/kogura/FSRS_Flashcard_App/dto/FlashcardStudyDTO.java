package com.kogura.FSRS_Flashcard_App.dto;

import java.time.Duration;
import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FlashcardStudyDTO {
  private Long id;
  private String question;
  private String answer;
  private String state; // "NEW", "LEARNING", or "REVIEW"
  private Instant dueDate;
  // The interval from now to the next due date if again, hard, good, and easy are used
  private Duration againInterval;
  private Duration hardInterval;
  private Duration goodInterval;
  private Duration easyInterval;
  // Media
  private String questionMediaUrl;
  private String questionMediaName;
  private String answerMediaUrl;
  private String answerMediaName;
}
