package com.kogura.FSRS_Flashcard_App.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VisibilityRequest {
  @JsonProperty("isPublic")
  private boolean isPublic;
}
