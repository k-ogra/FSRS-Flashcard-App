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

@RestController
@RequestMapping("/api/v0/decks/{deckId}/study")
public class StudyController {

  private final StudyService studyService;
  private final DeckRepository deckRepository;
  private final UserRepository userRepository;

  @Autowired
  public StudyController(StudyService studyService, DeckRepository deckRepository, UserRepository userRepository) {
    this.studyService = studyService;
    this.deckRepository = deckRepository;
    this.userRepository = userRepository;
  }

  private User getAuthenticatedUser() {
    String username = SecurityContextHolder.getContext().getAuthentication().getName();
    return userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));
  }

  private Deck getAuthorizedDeck(Long deckId) {
    User user = getAuthenticatedUser();
    Optional<Deck> optDeck = deckRepository.findById(deckId);
    if (optDeck.isEmpty() || !optDeck.get().getUser().getId().equals(user.getId())) {
      return null;
    }
    return optDeck.get();
  }

  @GetMapping("/new")
  public ResponseEntity<List<FlashcardStudyDTO>> getNewQueue(@PathVariable Long deckId) {
    if (getAuthorizedDeck(deckId) == null) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(studyService.getNewQueue(deckId));
  }

  @GetMapping("/learning")
  public ResponseEntity<List<FlashcardStudyDTO>> getLearningQueue(@PathVariable Long deckId) {
    if (getAuthorizedDeck(deckId) == null) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(studyService.getLearningQueue(deckId));
  }

  @GetMapping("/review")
  public ResponseEntity<List<FlashcardStudyDTO>> getReviewQueue(@PathVariable Long deckId) {
    if (getAuthorizedDeck(deckId) == null) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(studyService.getReviewQueue(deckId));
  }

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
