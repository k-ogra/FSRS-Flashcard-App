package com.kogura.FSRS_Flashcard_App.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kogura.FSRS_Flashcard_App.model.Deck;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class DeckResponse {
  private Long id;
  private String name;
  @JsonProperty("isPublic")
  private boolean isPublic;
  private String ownerUsername;
  private String sharedByUsername;
  private Instant createdAt;
  private int flashcardCount;

  public static DeckResponse fromDeck(Deck deck, String sharedByUsername) {
    return new DeckResponse(
        deck.getId(),
        deck.getName(),
        deck.isPublic(),
        deck.getUser().getUsername(),
        sharedByUsername,
        deck.getCreatedAt(),
        deck.getFlashcards() != null ? deck.getFlashcards().size() : 0
    );
  }
}
