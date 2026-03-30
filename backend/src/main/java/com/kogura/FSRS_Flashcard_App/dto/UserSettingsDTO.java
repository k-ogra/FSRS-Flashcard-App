package com.kogura.FSRS_Flashcard_App.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 * DTO for user settings.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserSettingsDTO {
  private int reviewAheadMinutes;
  private int dailyNewCardLimit;
  private int dailyReviewLimit;
}
