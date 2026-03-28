package com.kogura.FSRS_Flashcard_App.dto;

import lombok.Data;
import io.github.openspacedrepetition.Rating; 


@Data
public class ReviewRequest {
  private Long flashcardId;
  private Rating grade; // "AGAIN", "HARD", "GOOD", "EASY"
}
