package com.kogura.FSRS_Flashcard_App.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PresignedPostResponse {
  private String url;
  private Map<String, String> fields;
}
