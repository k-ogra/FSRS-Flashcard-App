package com.kogura.FSRS_Flashcard_App.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import com.kogura.FSRS_Flashcard_App.dto.CopyDeckRequest;
import com.kogura.FSRS_Flashcard_App.dto.DeckResponse;
import com.kogura.FSRS_Flashcard_App.dto.DeckStatsDTO;
import com.kogura.FSRS_Flashcard_App.dto.ShareRequest;
import com.kogura.FSRS_Flashcard_App.dto.UserSummaryDTO;
import com.kogura.FSRS_Flashcard_App.dto.VisibilityRequest;
import com.kogura.FSRS_Flashcard_App.service.MediaMetadataService;
import com.kogura.FSRS_Flashcard_App.service.S3Service;
import com.kogura.FSRS_Flashcard_App.service.StudyService;
import com.kogura.FSRS_Flashcard_App.model.MediaMetadata;
import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.model.SharedDeck;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.repository.DailyStudyProgressRepository;
import com.kogura.FSRS_Flashcard_App.repository.DeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.SharedDeckRepository;
import com.kogura.FSRS_Flashcard_App.model.UserSettings;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserSettingsRepository;

import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * REST controller for deck management endpoints under {@code /api/v0/decks}.
 *
 * <p>Handles CRUD operations on decks, visibility toggling, deck sharing,
 * and copying decks from other users. All endpoints require an authenticated
 * session except where otherwise noted.
 */
@RestController
@RequestMapping("/api/v0/decks")
public class DeckController {

  /** Logger for this controller. */
  private static final Logger log = LoggerFactory.getLogger(DeckController.class);

  /** Repository for persisting and querying {@link Deck} entities. */
  private final DeckRepository deckRepository;

  /** Repository for managing {@link SharedDeck} join records. */
  private final SharedDeckRepository sharedDeckRepository;

  /** Repository for looking up {@link User} entities by username or ID. */
  private final UserRepository userRepository;

  /** Repository for reading and persisting per-user {@link UserSettings}. */
  private final UserSettingsRepository userSettingsRepository;

  /** Repository for deleting daily study progress records when a deck is deleted. */
  private final DailyStudyProgressRepository dailyStudyProgressRepository;

  /** Service for computing per-deck FSRS study counts. */
  private final StudyService studyService;

  /** Service for refreshing pre-signed S3 download URLs on flashcard media. */
  private final MediaMetadataService mediaMetadataService;

  /** Service for S3 object operations (delete, copy). */
  private final S3Service s3Service;

  /**
   * Constructs a {@code DeckController} with all required dependencies.
   *
   * @param deckRepository               repository for deck persistence
   * @param sharedDeckRepository         repository for shared-deck records
   * @param userRepository               repository for user lookups
   * @param userSettingsRepository       repository for user settings
   * @param dailyStudyProgressRepository repository for daily study progress
   * @param studyService                 service for FSRS study count calculations
   * @param mediaMetadataService         service for media metadata and URL refresh
   * @param s3Service                    service for S3 object operations
   */
  @Autowired
  public DeckController(DeckRepository deckRepository, SharedDeckRepository sharedDeckRepository,
      UserRepository userRepository, UserSettingsRepository userSettingsRepository,
      DailyStudyProgressRepository dailyStudyProgressRepository, StudyService studyService,
      MediaMetadataService mediaMetadataService, S3Service s3Service) {
    this.deckRepository = deckRepository;
    this.sharedDeckRepository = sharedDeckRepository;
    this.userRepository = userRepository;
    this.userSettingsRepository = userSettingsRepository;
    this.dailyStudyProgressRepository = dailyStudyProgressRepository;
    this.studyService = studyService;
    this.mediaMetadataService = mediaMetadataService;
    this.s3Service = s3Service;
  }

  /**
   * Resolves the currently authenticated user from the Spring Security context.
   *
   * @return the authenticated {@link User}
   * @throws RuntimeException if the username from the security context has no matching user record
   */
  private User getAuthenticatedUser() throws RuntimeException {
    String username = SecurityContextHolder.getContext().getAuthentication().getName();
    return userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));
  }

  /**
   * Returns all decks owned by the authenticated user.
   *
   * <p>Each deck's {@code isShared} flag is set to {@code true} if at least one
   * active {@link SharedDeck} record exists for that deck.
   *
   * @return list of the authenticated user's decks, never {@code null}
   */
  @GetMapping
  public List<Deck> getAllDecks() {
    User user = getAuthenticatedUser();
    List<Deck> decks = deckRepository.findByUser(user);
    Set<Long> sharedDeckIds = new HashSet<>(sharedDeckRepository.findDeckIdsWithSharesByOwner(user));
    for (Deck deck : decks) {
      deck.setShared(sharedDeckIds.contains(deck.getId()));
    }
    return decks;
  }

  /**
   * Returns FSRS study statistics for all decks owned by the authenticated user.
   *
   * <p>Uses the user's {@code reviewAheadMinutes} setting to determine the
   * cutoff time for cards that are due for review. If no {@link UserSettings}
   * record exists, default settings are created and persisted.
   *
   * @return {@code 200 OK} with a map of deck ID to {@link DeckStatsDTO}
   */
  @GetMapping("/stats")
  public ResponseEntity<Map<Long, DeckStatsDTO>> getAllDeckStats() {
    User user = getAuthenticatedUser();
    UserSettings settings = userSettingsRepository.findByUser(user)
        .orElseGet(() -> {
          UserSettings s = new UserSettings();
          s.setUser(user);
          return userSettingsRepository.save(s);
        });
    List<Deck> decks = deckRepository.findByUser(user);
    Map<Long, DeckStatsDTO> statsMap = new java.util.HashMap<>();
    for (Deck deck : decks) {
      statsMap.put(deck.getId(), studyService.getDeckStudyCounts(
          deck.getId(), settings.getReviewAheadMinutes(),
          user, deck, settings.getDailyNewCardLimit(), settings.getDailyReviewLimit()));
    }
    return ResponseEntity.ok(statsMap);
  }

  /**
   * Returns all decks marked as public, accessible without authentication.
   *
   * @return list of public decks as {@link DeckResponse} projections
   */
  @GetMapping("/public")
  public List<DeckResponse> getPublicDecks() {
    return deckRepository.findByIsPublicTrue().stream()
        .map(d -> DeckResponse.fromDeck(d, null))
        .collect(Collectors.toList());
  }

  /**
   * Returns all decks that have been shared with the authenticated user by other users.
   *
   * @return list of shared decks as {@link DeckResponse} projections, each including
   *         the username of the user who shared the deck
   */
  @GetMapping("/shared")
  public List<DeckResponse> getSharedDecks() {
    User user = getAuthenticatedUser();
    return sharedDeckRepository.findByUser(user).stream()
        .map(sd -> DeckResponse.fromDeck(sd.getDeck(), sd.getSharer().getUsername()))
        .collect(Collectors.toList());
  }

  /**
   * Returns a single deck by ID, provided the authenticated user is allowed to view it.
   *
   * <p>A user may view a deck if they own it, it has been shared with them, or it is public.
   * Pre-signed S3 download URLs are refreshed for all flashcard media before the response
   * is serialized.
   *
   * @param id the deck ID
   * @return {@code 200 OK} with the {@link Deck}, or {@code 404 Not Found} if the deck
   *         does not exist or the user has no access
   */
  @GetMapping("/{id}")
  public ResponseEntity<Deck> getDeckById(@PathVariable Long id) {
    User user = getAuthenticatedUser();
    Optional<Deck> optDeck = deckRepository.findById(id);
    if (optDeck.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    Deck d = optDeck.get();
    boolean isOwner = d.getUser().getId().equals(user.getId());
    boolean isShared = sharedDeckRepository.existsByDeckAndUser(d, user);
    boolean isPublicDeck = d.isPublic();
    if (!isOwner && !isShared && !isPublicDeck) {
      return ResponseEntity.notFound().build();
    }
    mediaMetadataService.refreshDownloadUrlsForFlashcards(d.getFlashcards());
    return ResponseEntity.ok(d);
  }

  /**
   * Creates a new deck for the authenticated user.
   *
   * @param deck the deck to create; must have a non-blank {@code name}
   * @return {@code 200 OK} with the saved {@link Deck}, {@code 400 Bad Request} if the name
   *         is blank, or {@code 409 Conflict} if a deck with the same name already exists
   */
  @PostMapping
  public ResponseEntity<?> createDeck(@RequestBody Deck deck) {
    User user = getAuthenticatedUser();

    String name = deck.getName();
    if (name == null || name.trim().isEmpty()) {
      return ResponseEntity.badRequest()
          .body(Map.of("message", "Deck name cannot be empty"));
    }

    deck.setName(name.trim());

    if (deckRepository.existsByNameAndUser(deck.getName(), user)) {
      return ResponseEntity.status(409)
          .body(Map.of("message", "A deck with this name already exists"));
    }

    deck.setUser(user);
    try {
      Deck saved = deckRepository.save(deck);
      return ResponseEntity.ok(saved);
    } catch (DataIntegrityViolationException e) {
      return ResponseEntity.status(409)
          .body(Map.of("message", "A deck with this name already exists"));
    }
  }

  /**
   * Deletes a deck owned by the authenticated user, along with all associated S3 media,
   * daily study progress records, and shared-deck records.
   *
   * @param id the deck ID to delete
   * @return {@code 204 No Content} on success, or {@code 404 Not Found} if the deck does
   *         not exist or is not owned by the authenticated user
   */
  @Transactional
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteDeck(@PathVariable Long id) {
    User user = getAuthenticatedUser();
    Optional<Deck> optionalDeck = deckRepository.findById(id);
    if (optionalDeck.isEmpty() || !optionalDeck.get().getUser().getId().equals(user.getId())) {
      return ResponseEntity.notFound().build();
    }
    Deck deck = optionalDeck.get();

    List<String> s3Keys = S3Service.collectS3Keys(deck.getFlashcards());
    s3Service.deleteObjects(s3Keys);

    dailyStudyProgressRepository.deleteByDeck(deck);
    sharedDeckRepository.deleteByDeck(deck);
    deckRepository.deleteById(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Toggles the public visibility of a deck owned by the authenticated user.
   *
   * @param id      the deck ID
   * @param request the visibility request containing the desired {@code isPublic} value
   * @return {@code 200 OK} with the updated {@link DeckResponse}, or {@code 404 Not Found}
   *         if the deck does not exist or is not owned by the authenticated user
   */
  @PatchMapping("/{id}/visibility")
  public ResponseEntity<?> toggleVisibility(@PathVariable Long id, @RequestBody VisibilityRequest request) {
    User user = getAuthenticatedUser();
    Optional<Deck> optionalDeck = deckRepository.findById(id);
    if (optionalDeck.isEmpty() || !optionalDeck.get().getUser().getId().equals(user.getId())) {
      return ResponseEntity.notFound().build();
    }
    Deck deck = optionalDeck.get();
    deck.setPublic(request.isPublic());
    deckRepository.save(deck);
    return ResponseEntity.ok(DeckResponse.fromDeck(deck, null));
  }

  /**
   * Returns the list of users that a deck has been shared with.
   *
   * @param id the deck ID
   * @return {@code 200 OK} with a list of {@link UserSummaryDTO} recipients, or
   *         {@code 404 Not Found} if the deck does not exist or is not owned by the
   *         authenticated user
   */
  @GetMapping("/{id}/share")
  public ResponseEntity<?> getDeckRecipients(@PathVariable Long id) {
    User user = getAuthenticatedUser();
    Optional<Deck> optionalDeck = deckRepository.findById(id);
    if (optionalDeck.isEmpty() || !optionalDeck.get().getUser().getId().equals(user.getId())) {
      return ResponseEntity.notFound().build();
    }
    Deck deck = optionalDeck.get();
    List<UserSummaryDTO> recipients = sharedDeckRepository.findByDeck(deck).stream()
        .map(sd -> new UserSummaryDTO(sd.getUser().getId(), sd.getUser().getUsername()))
        .collect(Collectors.toList());
    return ResponseEntity.ok(recipients);
  }

  /**
   * Shares a deck owned by the authenticated user with another user.
   *
   * @param id      the deck ID to share
   * @param request the share request containing the recipient's username
   * @return {@code 200 OK} on success, {@code 400 Bad Request} if the username is blank,
   *         unknown, or refers to the owner, {@code 404 Not Found} if the deck does not
   *         exist or is not owned by the authenticated user, or {@code 409 Conflict} if
   *         the deck is already shared with the specified user
   */
  @PostMapping("/{id}/share")
  public ResponseEntity<?> shareDeck(@PathVariable Long id, @RequestBody ShareRequest request) {
    User owner = getAuthenticatedUser();
    Optional<Deck> optionalDeck = deckRepository.findById(id);
    if (optionalDeck.isEmpty() || !optionalDeck.get().getUser().getId().equals(owner.getId())) {
      return ResponseEntity.notFound().build();
    }
    Deck deck = optionalDeck.get();

    String recipientUsername = request.getUsername();
    if (recipientUsername == null || recipientUsername.trim().isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("message", "Username is required"));
    }

    Optional<User> recipientOpt = userRepository.findByUsername(recipientUsername.trim());
    if (recipientOpt.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("message", "User not found"));
    }
    User recipient = recipientOpt.get();

    if (recipient.getId().equals(owner.getId())) {
      return ResponseEntity.badRequest().body(Map.of("message", "Cannot share a deck with yourself"));
    }

    if (sharedDeckRepository.existsByDeckAndUser(deck, recipient)) {
      return ResponseEntity.status(409).body(Map.of("message", "Deck already shared with this user"));
    }

    SharedDeck sharedDeck = new SharedDeck();
    sharedDeck.setDeck(deck);
    sharedDeck.setUser(recipient);
    sharedDeck.setSharer(owner);
    sharedDeckRepository.save(sharedDeck);

    return ResponseEntity.ok(Map.of("message", "Deck shared successfully"));
  }

  /**
   * Revokes a deck share, removing access for the specified recipient user.
   *
   * @param id     the deck ID
   * @param userId the ID of the user whose access should be revoked
   * @return {@code 204 No Content} on success, or {@code 404 Not Found} if the deck or
   *         recipient user does not exist, or the deck is not owned by the authenticated user
   */
  @DeleteMapping("/{id}/share/{userId}")
  @Transactional
  public ResponseEntity<?> unshareDeck(@PathVariable Long id, @PathVariable Long userId) {
    User owner = getAuthenticatedUser();
    Optional<Deck> optionalDeck = deckRepository.findById(id);
    if (optionalDeck.isEmpty() || !optionalDeck.get().getUser().getId().equals(owner.getId())) {
      return ResponseEntity.notFound().build();
    }
    Deck deck = optionalDeck.get();

    Optional<User> recipientOpt = userRepository.findById(userId);
    if (recipientOpt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    sharedDeckRepository.deleteByDeckAndUser(deck, recipientOpt.get());
    return ResponseEntity.noContent().build();
  }

  /**
   * Copies a deck that is shared with or public to the authenticated user into their own library.
   *
   * <p>Each flashcard is deep-copied, and any S3 media objects (question and answer) are
   * duplicated under the requesting user's S3 prefix. Media copy failures are logged as
   * warnings but do not abort the operation.
   *
   * @param id      the source deck ID to copy
   * @param request the copy request containing the desired name for the new deck
   * @return {@code 200 OK} with the newly created {@link Deck}, {@code 400 Bad Request} if the
   *         name is blank or the user owns the source deck, {@code 404 Not Found} if the deck
   *         does not exist or the user has no access, or {@code 409 Conflict} if a deck with
   *         the requested name already exists in the user's library
   */
  @PostMapping("/{id}/copy")
  public ResponseEntity<?> copyDeck(@PathVariable Long id, @RequestBody CopyDeckRequest request) {
    User user = getAuthenticatedUser();
    Optional<Deck> optDeck = deckRepository.findById(id);
    if (optDeck.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    Deck source = optDeck.get();

    boolean isOwner = source.getUser().getId().equals(user.getId());
    if (isOwner) {
      return ResponseEntity.badRequest()
          .body(Map.of("message", "Cannot copy your own deck"));
    }

    // Check if the deck is shared with the user or is public if not, return not found
    boolean isShared = sharedDeckRepository.existsByDeckAndUser(source, user);
    boolean isPublicDeck = source.isPublic();
    if (!isShared && !isPublicDeck) {
      return ResponseEntity.notFound().build();
    }

    String name = request.getName();
    if (name == null || name.trim().isEmpty()) {
      return ResponseEntity.badRequest()
          .body(Map.of("message", "Deck name cannot be empty"));
    }
    name = name.trim();

    if (deckRepository.existsByNameAndUser(name, user)) {
      return ResponseEntity.status(409)
          .body(Map.of("message", "A deck with this name already exists"));
    }

    Deck newDeck = new Deck();
    newDeck.setName(name);
    newDeck.setUser(user);
    newDeck.setPublic(false);

    List<Flashcard> copiedCards = new ArrayList<>();
    for (final Flashcard src : source.getFlashcards()) {
      Flashcard copy = new Flashcard();
      copy.setQuestion(src.getQuestion());
      copy.setAnswer(src.getAnswer());

      // Copy question media
      final MediaMetadata qMeta = src.getQuestionMediaMetadata();
      if (qMeta != null && qMeta.getS3Key() != null) {
        try {
          String newKey = String.format("uploads/%s/%s/%s",
              user.getUsername(), UUID.randomUUID(), qMeta.getName());
          s3Service.copyObject(qMeta.getS3Key(), newKey);
          copy.setQuestionMediaMetadata(mediaMetadataService.copyMediaMetadata(qMeta, newKey));
        } catch (Exception e) {
          log.warn("Failed to copy question media for flashcard {}: {}", src.getId(), e.getMessage());
        }
      }

      // Copy answer media
      final MediaMetadata aMeta = src.getAnswerMediaMetadata();
      if (aMeta != null && aMeta.getS3Key() != null) {
        try {
          String newKey = String.format("uploads/%s/%s/%s",
              user.getUsername(), UUID.randomUUID(), aMeta.getName());
          s3Service.copyObject(aMeta.getS3Key(), newKey);
          copy.setAnswerMediaMetadata(mediaMetadataService.copyMediaMetadata(aMeta, newKey));
        } catch (Exception e) {
          log.warn("Failed to copy answer media for flashcard {}: {}", src.getId(), e.getMessage());
        }
      }

      copiedCards.add(copy);
    }
    newDeck.setFlashcards(copiedCards);

    try {
      Deck saved = deckRepository.save(newDeck);
      return ResponseEntity.ok(saved);
    } catch (DataIntegrityViolationException e) {
      return ResponseEntity.status(409)
          .body(Map.of("message", "A deck with this name already exists"));
    }
  }
}
