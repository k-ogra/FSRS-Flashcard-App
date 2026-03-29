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
  /**
   * The number of minutes ahead of the current time that cards should be reviewed.
   */
  private int reviewAheadMinutes;
}
