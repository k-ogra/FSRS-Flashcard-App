package com.kogura.FSRS_Flashcard_App.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import com.kogura.FSRS_Flashcard_App.dto.FlashcardStudyDTO;
import com.kogura.FSRS_Flashcard_App.dto.ReviewRequest;
import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.model.UserSettings;
import com.kogura.FSRS_Flashcard_App.repository.DeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserSettingsRepository;
import com.kogura.FSRS_Flashcard_App.service.StudyService;

import java.util.List;
import java.util.Optional;

/**
 * Controller for the study session endpoints.
 */
@RestController
@RequestMapping("/api/v0/decks/{deckId}/study")
public class StudyController {

  private final StudyService studyService;
  private final DeckRepository deckRepository;
  private final UserRepository userRepository;
  private final UserSettingsRepository userSettingsRepository;

  @Autowired
  public StudyController(StudyService studyService, DeckRepository deckRepository,
      UserRepository userRepository, UserSettingsRepository userSettingsRepository) {
    this.studyService = studyService;
    this.deckRepository = deckRepository;
    this.userRepository = userRepository;
    this.userSettingsRepository = userSettingsRepository;
  }

  private User getAuthenticatedUser() {
    String username = SecurityContextHolder.getContext().getAuthentication().getName();
    return userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));
  }

  private Deck getAuthorizedDeck(Long deckId, User user) {
    Optional<Deck> optDeck = deckRepository.findById(deckId);
    if (optDeck.isEmpty() || !optDeck.get().getUser().getId().equals(user.getId())) {
      return null;
    }
    return optDeck.get();
  }

  @GetMapping("/queue")
  public ResponseEntity<List<FlashcardStudyDTO>> getStudyQueue(@PathVariable Long deckId) {
    User user = getAuthenticatedUser();
    Deck deck = getAuthorizedDeck(deckId, user);
    if (deck == null) return ResponseEntity.notFound().build();
    UserSettings settings = userSettingsRepository.findByUser(user).orElse(null);
    int newLimit = settings != null ? settings.getDailyNewCardLimit() : 20;
    int reviewLimit = settings != null ? settings.getDailyReviewLimit() : 200;
    int aheadMinutes = settings != null ? settings.getReviewAheadMinutes() : 20;
    return ResponseEntity.ok(
        studyService.getStudyQueue(deckId, aheadMinutes, user, deck, newLimit, reviewLimit));
  }

  @PostMapping("/review")
  public ResponseEntity<FlashcardStudyDTO> reviewCard(@PathVariable Long deckId, @RequestBody ReviewRequest request) {
    User user = getAuthenticatedUser();
    Deck deck = getAuthorizedDeck(deckId, user);
    if (deck == null) return ResponseEntity.notFound().build();

    boolean cardBelongsToDeck = deck.getFlashcards().stream()
        .anyMatch(f -> f.getId().equals(request.getFlashcardId()));
    if (!cardBelongsToDeck) {
      return ResponseEntity.badRequest().build();
    }

    FlashcardStudyDTO updated = studyService.reviewCard(request.getFlashcardId(), request.getGrade(), user, deck);
    return ResponseEntity.ok(updated);
  }
}
