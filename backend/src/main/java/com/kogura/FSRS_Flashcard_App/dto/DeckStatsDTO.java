package com.kogura.FSRS_Flashcard_App.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeckStatsDTO {
  private int newCount;
  private int learningCount;
  private int reviewCount;
}
