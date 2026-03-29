package com.kogura.FSRS_Flashcard_App.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import com.kogura.FSRS_Flashcard_App.dto.FlashcardStudyDTO;
import com.kogura.FSRS_Flashcard_App.dto.ReviewRequest;
import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.repository.DeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;
import com.kogura.FSRS_Flashcard_App.service.StudyService;

import java.util.List;
import java.util.Optional;

/**
 * Controller for the study session endpoints.
 */
@RestController
@RequestMapping("/api/v0/decks/{deckId}/study")
public class StudyController {

  /**
   * The study service.
   */
  private final StudyService studyService;
  /**
   * The deck repository.
   */
  private final DeckRepository deckRepository;
  /**
   * The user repository.
   */
  private final UserRepository userRepository;

  /**
   * Constructor for the StudyController.
   * @param studyService The study service.
   * @param deckRepository The deck repository.
   * @param userRepository The user repository.
   */
  @Autowired
  public StudyController(StudyService studyService, DeckRepository deckRepository, UserRepository userRepository) {
    this.studyService = studyService;
    this.deckRepository = deckRepository;
    this.userRepository = userRepository;
  }

  /**
   * Get the authenticated user.
   * @return The authenticated user.
   */
  private User getAuthenticatedUser() {
    String username = SecurityContextHolder.getContext().getAuthentication().getName();
    return userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));
  }

  /**
   * Get the authorized deck for the authenticated user.
   * @param deckId The ID of the deck.
   * @return The authorized deck.
   */
  private Deck getAuthorizedDeck(Long deckId) {
    User user = getAuthenticatedUser();
    Optional<Deck> optDeck = deckRepository.findById(deckId);
    if (optDeck.isEmpty() || !optDeck.get().getUser().getId().equals(user.getId())) {
      return null;
    }
    return optDeck.get();
  }

  /**
   * Get the new queue for the authenticated user.
   * @param deckId The ID of the deck.
   * @return The new queue.
   */
  @GetMapping("/new")
  public ResponseEntity<List<FlashcardStudyDTO>> getNewQueue(@PathVariable Long deckId) {
    if (getAuthorizedDeck(deckId) == null) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(studyService.getNewQueue(deckId));
  }

  /**
   * Get the learning queue for the authenticated user.
   * @param deckId The ID of the deck.
   * @param aheadMinutes The number of minutes ahead of the current time that cards should be reviewed.
   * @return The learning queue.
   */
  @GetMapping("/learning")
  public ResponseEntity<List<FlashcardStudyDTO>> getLearningQueue(
      @PathVariable Long deckId,
      @RequestParam(defaultValue = "20") int aheadMinutes) {
    if (getAuthorizedDeck(deckId) == null) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(studyService.getLearningQueue(deckId, aheadMinutes));
  }

  /**
   * Get the review queue for the authenticated user.
   * @param deckId The ID of the deck.
   * @param aheadMinutes The number of minutes ahead of the current time that cards should be reviewed.
   * @return The review queue.
   */
  @GetMapping("/review")
  public ResponseEntity<List<FlashcardStudyDTO>> getReviewQueue(
      @PathVariable Long deckId,
      @RequestParam(defaultValue = "20") int aheadMinutes) {
    if (getAuthorizedDeck(deckId) == null) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(studyService.getReviewQueue(deckId, aheadMinutes));
  }

  /**
   * Review a card for the authenticated user.
   * @param deckId The ID of the deck.
   * @param request The review request.
   * @return The updated card.
   */
  @PostMapping("/review")
  public ResponseEntity<FlashcardStudyDTO> reviewCard(@PathVariable Long deckId, @RequestBody ReviewRequest request) {
    Deck deck = getAuthorizedDeck(deckId);
    if (deck == null) return ResponseEntity.notFound().build();

    // Verify the flashcard belongs to this deck
    boolean cardBelongsToDeck = deck.getFlashcards().stream()
        .anyMatch(f -> f.getId().equals(request.getFlashcardId()));
    if (!cardBelongsToDeck) {
      return ResponseEntity.badRequest().build();
    }

    FlashcardStudyDTO updated = studyService.reviewCard(request.getFlashcardId(), request.getGrade());
    return ResponseEntity.ok(updated);
  }
}
