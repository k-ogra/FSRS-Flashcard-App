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
 * REST controller for spaced-repetition study session endpoints under
 * {@code /api/v0/decks/{deckId}/study}.
 *
 * <p>Exposes two operations: fetching the study queue for a deck and submitting a
 * flashcard review with an FSRS {@code Rating} grade. All endpoints require an
 * authenticated session, and ownership of the target deck is enforced before any
 * data is returned or mutated.
 */
@RestController
@RequestMapping("/api/v0/decks/{deckId}/study")
public class StudyController {

  /** Service that drives FSRS scheduling: builds study queues and persists review outcomes. */
  private final StudyService studyService;

  /** Repository for loading and ownership-checking {@link Deck} entities. */
  private final DeckRepository deckRepository;

  /** Repository for resolving the authenticated {@link User} by username. */
  private final UserRepository userRepository;

  /**
   * Repository for loading per-user study preferences ({@code dailyNewCardLimit},
   * {@code dailyReviewLimit}, {@code reviewAheadMinutes}).
   */
  private final UserSettingsRepository userSettingsRepository;

  /**
   * Constructs a {@code StudyController} with all required dependencies.
   *
   * @param studyService           service for FSRS queue building and review processing
   * @param deckRepository         repository for deck lookups and ownership checks
   * @param userRepository         repository for user lookups
   * @param userSettingsRepository repository for per-user study settings
   */
  @Autowired
  public StudyController(StudyService studyService, DeckRepository deckRepository,
      UserRepository userRepository, UserSettingsRepository userSettingsRepository) {
    this.studyService = studyService;
    this.deckRepository = deckRepository;
    this.userRepository = userRepository;
    this.userSettingsRepository = userSettingsRepository;
  }

  /**
   * Resolves the currently authenticated user from the Spring Security context.
   *
   * @return the authenticated {@link User}
   * @throws RuntimeException if the username from the security context has no matching user record
   */
  private User getAuthenticatedUser() {
    String username = SecurityContextHolder.getContext().getAuthentication().getName();
    return userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));
  }

  /**
   * Loads a deck by ID and verifies that it is owned by the given user.
   *
   * @param deckId the ID of the deck to load
   * @param user   the authenticated user who must own the deck
   * @return the {@link Deck} if it exists and is owned by {@code user}, or {@code null} otherwise
   */
  private Deck getAuthorizedDeck(Long deckId, User user) {
    Optional<Deck> optDeck = deckRepository.findById(deckId);
    if (optDeck.isEmpty() || !optDeck.get().getUser().getId().equals(user.getId())) {
      return null;
    }
    return optDeck.get();
  }

  /**
   * Returns the FSRS study queue for the specified deck.
   *
   * <p>The queue is filtered by the authenticated user's {@link UserSettings}: new-card daily
   * limit, review daily limit, and review-ahead window (in minutes). When no settings record
   * exists the defaults are 20 new cards, 200 reviews, and 20 minutes ahead.
   *
   * @param deckId the ID of the deck whose study queue should be returned
   * @return {@code 200 OK} with an ordered list of {@link FlashcardStudyDTO} objects ready for
   *         study, or {@code 404 Not Found} if the deck does not exist or is not owned by the
   *         authenticated user
   */
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

  /**
   * Submits a review for a single flashcard and advances its FSRS schedule.
   *
   * <p>The flashcard must belong to the specified deck. The {@link ReviewRequest} body must
   * contain the flashcard ID and the FSRS {@code Rating} grade ({@code AGAIN}, {@code HARD},
   * {@code GOOD}, or {@code EASY}). After the review is persisted the updated
   * {@link FlashcardStudyDTO} — including the new state and next-due date — is returned.
   *
   * @param deckId  the ID of the deck that owns the flashcard being reviewed
   * @param request the review request containing {@code flashcardId} and {@code grade}
   * @return {@code 200 OK} with the updated {@link FlashcardStudyDTO},
   *         {@code 400 Bad Request} if the flashcard does not belong to the specified deck,
   *         or {@code 404 Not Found} if the deck does not exist or is not owned by the
   *         authenticated user
   */
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
