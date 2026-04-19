package com.kogura.FSRS_Flashcard_App.controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.model.MediaMetadata;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.repository.DeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.FlashcardRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;
import com.kogura.FSRS_Flashcard_App.service.MediaMetadataService;
import com.kogura.FSRS_Flashcard_App.service.S3Service;

import jakarta.transaction.Transactional;
import lombok.Data;

/**
 * REST controller for flashcard management endpoints under {@code /api/v0/flashcards}.
 *
 * <p>Handles CRUD operations on flashcards and media attachment/removal. All endpoints
 * require an authenticated session. Ownership is enforced by verifying that the flashcard's
 * parent deck belongs to the authenticated user before any mutating operation.
 */
@RestController
@RequestMapping("/api/v0/flashcards")
public class FlashcardController {

  /** Repository for persisting and querying {@link Flashcard} entities. */
  private final FlashcardRepository flashcardRepository;

  /** Repository for loading the parent {@link Deck} during ownership checks and card creation. */
  private final DeckRepository deckRepository;

  /** Repository for resolving the authenticated {@link User} by username. */
  private final UserRepository userRepository;

  /** Service for S3 object operations (delete, metadata lookup). */
  private final S3Service s3Service;

  /** Service for presigned-URL refresh and media attachment/detachment on flashcards. */
  private final MediaMetadataService mediaMetadataService;

  /**
   * Constructs a {@code FlashcardController} with all required dependencies.
   *
   * @param flashcardRepository repository for flashcard persistence
   * @param deckRepository      repository for deck lookups and cascade saves
   * @param userRepository      repository for user lookups
   * @param s3Service           service for S3 object operations
   * @param mediaMetadataService service for media metadata and URL refresh
   */
  @Autowired
  public FlashcardController(FlashcardRepository flashcardRepository, DeckRepository deckRepository,
      UserRepository userRepository, S3Service s3Service, MediaMetadataService mediaMetadataService) {
    this.flashcardRepository = flashcardRepository;
    this.deckRepository = deckRepository;
    this.userRepository = userRepository;
    this.s3Service = s3Service;
    this.mediaMetadataService = mediaMetadataService;
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
   * Returns all flashcards in the database.
   *
   * <p><b>Note:</b> this endpoint applies no per-user filtering; all flashcards from all
   * decks are returned. An authenticated session is required by the security filter chain.
   *
   * @return list of all {@link Flashcard} records, never {@code null}
   */
  @GetMapping
  public List<Flashcard> getAllFlashcards() {
    List<Flashcard> flashcards = flashcardRepository.findAll();
    return flashcards;
  }

  /**
   * Returns a single flashcard by ID.
   *
   * @param id the flashcard ID
   * @return {@code 200 OK} with the {@link Flashcard}, or {@code 404 Not Found} if no
   *         flashcard with the given ID exists
   */
  @GetMapping("/{id}")
  public ResponseEntity<Flashcard> getFlashcardById(@PathVariable Long id) {
    Optional<Flashcard> flashcard = flashcardRepository.findById(id);
    if (flashcard.isPresent()) {
      return ResponseEntity.ok(flashcard.get());
    }
    return ResponseEntity.notFound().build();
  }

  /**
   * Creates a new flashcard and adds it to the specified deck.
   *
   * <p>The flashcard is appended to the deck's flashcard list and persisted via a cascade
   * save on the parent deck.
   *
   * @param request the creation request containing {@code deckId}, {@code question}, and
   *                {@code answer}; all three fields are required
   * @return {@code 200 OK} with the persisted {@link Flashcard}, {@code 400 Bad Request}
   *         if any required field is missing, or {@code 404 Not Found} if the deck does
   *         not exist
   */
  @PostMapping
  public ResponseEntity<Flashcard> createFlashcard(@RequestBody FlashcardCreateRequest request) {
    if (request.getDeckId() == null || request.getQuestion() == null || request.getAnswer() == null) {
      return ResponseEntity.badRequest().build();
    }

    Optional<Deck> optionalDeck = deckRepository.findById(request.getDeckId());
    if (optionalDeck.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    Deck deck = optionalDeck.get();
    Flashcard newFlashcard = new Flashcard();
    newFlashcard.setQuestion(request.getQuestion());
    newFlashcard.setAnswer(request.getAnswer());
    deck.getFlashcards().add(newFlashcard);
    Deck savedDeck = deckRepository.save(deck);
    Flashcard savedFlashcard = savedDeck.getFlashcards().get(savedDeck.getFlashcards().size() - 1);
    return ResponseEntity.ok(savedFlashcard);
  }

  /**
   * Updates the question and/or answer of an existing flashcard.
   *
   * <p>Only fields present (non-null) in the request body are applied; omitting a field
   * leaves the existing value unchanged. Ownership is enforced: the flashcard's parent deck
   * must belong to the authenticated user.
   *
   * <p>Presigned S3 download URLs for any attached media are refreshed after the save.
   *
   * @param id      the flashcard ID to update
   * @param request the update request; {@code question} and {@code answer} are both optional
   * @return {@code 200 OK} with the updated {@link Flashcard}, or {@code 404 Not Found} if
   *         the flashcard does not exist or its parent deck is not owned by the authenticated user
   */
  @PutMapping("/{id}")
  public ResponseEntity<Flashcard> updateFlashcard(@PathVariable Long id, @RequestBody FlashcardUpdateRequest request) {
    Optional<Flashcard> optionalFlashcard = flashcardRepository.findById(id);
    if (optionalFlashcard.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    Flashcard flashcard = optionalFlashcard.get();

    User user = getAuthenticatedUser();
    Optional<Deck> deckOpt = deckRepository.findById(flashcard.getDeckId());
    if (deckOpt.isEmpty() || !deckOpt.get().getUser().getId().equals(user.getId())) {
      return ResponseEntity.notFound().build();
    }

    if (request.getQuestion() != null) {
      flashcard.setQuestion(request.getQuestion());
    }
    if (request.getAnswer() != null) {
      flashcard.setAnswer(request.getAnswer());
    }

    Flashcard updated = flashcardRepository.save(flashcard);
    mediaMetadataService.refreshDownloadUrlIfNeeded(updated.getQuestionMediaMetadata());
    mediaMetadataService.refreshDownloadUrlIfNeeded(updated.getAnswerMediaMetadata());
    return ResponseEntity.ok(updated);
  }

  /**
   * Deletes a flashcard owned by the authenticated user, along with any associated S3 media.
   *
   * <p>Ownership is enforced: the flashcard's parent deck must belong to the authenticated user.
   *
   * @param id the flashcard ID to delete
   * @return {@code 204 No Content} on success, or {@code 404 Not Found} if the flashcard does
   *         not exist or its parent deck is not owned by the authenticated user
   */
  @Transactional
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteFlashcard(@PathVariable Long id) {
    Optional<Flashcard> optionalFlashcard = flashcardRepository.findById(id);
    if (optionalFlashcard.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    Flashcard flashcard = optionalFlashcard.get();

    User user = getAuthenticatedUser();
    Optional<Deck> deckOpt = deckRepository.findById(flashcard.getDeckId());
    if (deckOpt.isEmpty() || !deckOpt.get().getUser().getId().equals(user.getId())) {
      return ResponseEntity.notFound().build();
    }

    List<String> s3Keys = S3Service.collectS3Keys(List.of(flashcard));
    s3Service.deleteObjects(s3Keys);

    flashcardRepository.deleteById(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Attaches an S3-hosted media object to either the question or answer side of a flashcard.
   *
   * <p>Before attaching, the S3 object's metadata is retrieved and validated: the stored
   * {@code flashcardid} must match the path variable and {@code isquestion} must match the
   * requested side. This prevents reassigning media uploaded for a different flashcard or side.
   *
   * <p>Ownership is enforced: the flashcard's parent deck must belong to the authenticated user.
   *
   * @param id      the flashcard ID to attach media to
   * @param request the attach request containing {@code side} ({@code "question"} or
   *                {@code "answer"}), {@code s3ObjectKey}, and {@code fileName}
   * @return {@code 200 OK} with the updated {@link Flashcard}, {@code 400 Bad Request} if
   *         required fields are missing/blank or the S3 metadata lookup fails,
   *         {@code 403 Forbidden} if the S3 metadata does not match the flashcard and side,
   *         or {@code 404 Not Found} if the flashcard does not exist or is not owned by the
   *         authenticated user
   */
  @Transactional
  @PostMapping("/{id}/media")
  public ResponseEntity<Flashcard> attachMedia(@PathVariable Long id, @RequestBody AttachMediaRequest request) {
    if (request.getSide() == null
        || (!"question".equals(request.getSide()) && !"answer".equals(request.getSide()))) {
      return ResponseEntity.badRequest().build();
    }
    if (request.getS3ObjectKey() == null || request.getS3ObjectKey().isBlank()
        || request.getFileName() == null || request.getFileName().isBlank()) {
      return ResponseEntity.badRequest().build();
    }

    Optional<Flashcard> opt = flashcardRepository.findById(id);
    if (opt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    Flashcard flashcard = opt.get();

    User user = getAuthenticatedUser();
    Optional<Deck> deckOpt = deckRepository.findById(flashcard.getDeckId());
    if (deckOpt.isEmpty() || !deckOpt.get().getUser().getId().equals(user.getId())) {
      return ResponseEntity.notFound().build();
    }

    boolean isQuestion = "question".equals(request.getSide());

    Map<String, String> metadata;
    try {
      metadata = s3Service.getObjectMetadata(request.getS3ObjectKey());
    } catch (Exception e) {
      return ResponseEntity.badRequest().build();
    }
    String metaFlashcardId = metadata.get("flashcardid");
    String metaIsQuestion = metadata.get("isquestion");
    if (!String.valueOf(id).equals(metaFlashcardId)
        || !String.valueOf(isQuestion).equals(metaIsQuestion)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    mediaMetadataService.attachMediaToFlashcard(flashcard, request.getS3ObjectKey(), request.getFileName(), isQuestion);
    Flashcard saved = flashcardRepository.save(flashcard);
    return ResponseEntity.ok(saved);
  }

  /**
   * Removes the media attachment from either the question or answer side of a flashcard.
   *
   * <p>If the specified side has no media attached, the request is treated as a no-op and
   * {@code 204 No Content} is returned. When media exists, the S3 object is deleted and the
   * {@link MediaMetadata} reference is cleared before the flashcard is saved.
   *
   * <p>Ownership is enforced: the flashcard's parent deck must belong to the authenticated user.
   *
   * @param id   the flashcard ID whose media should be removed
   * @param side the side to clear; must be {@code "question"} or {@code "answer"}
   * @return {@code 204 No Content} on success or when no media is attached, {@code 400 Bad
   *         Request} if {@code side} is not a recognised value, or {@code 404 Not Found} if
   *         the flashcard does not exist or is not owned by the authenticated user
   */
  @Transactional
  @DeleteMapping("/{id}/media")
  public ResponseEntity<Void> deleteFlashcardMedia(@PathVariable Long id, @RequestParam String side) {
    if (!"question".equals(side) && !"answer".equals(side)) {
      return ResponseEntity.badRequest().build();
    }

    Optional<Flashcard> opt = flashcardRepository.findById(id);
    if (opt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    Flashcard flashcard = opt.get();

    User user = getAuthenticatedUser();
    Optional<Deck> deckOpt = deckRepository.findById(flashcard.getDeckId());
    if (deckOpt.isEmpty() || !deckOpt.get().getUser().getId().equals(user.getId())) {
      return ResponseEntity.notFound().build();
    }

    MediaMetadata meta = "question".equals(side)
        ? flashcard.getQuestionMediaMetadata()
        : flashcard.getAnswerMediaMetadata();

    if (meta == null || meta.getS3Key() == null) {
      return ResponseEntity.noContent().build();
    }

    s3Service.deleteObjects(List.of(meta.getS3Key()));

    if ("question".equals(side)) {
      flashcard.setQuestionMediaMetadata(null);
    } else {
      flashcard.setAnswerMediaMetadata(null);
    }
    flashcardRepository.save(flashcard);
    return ResponseEntity.noContent().build();
  }

  /**
   * Request body for {@link #createFlashcard}.
   * All three fields are required; a missing field causes a {@code 400 Bad Request} response.
   */
  @Data
  static class FlashcardCreateRequest {

    /** ID of the deck to add the new flashcard to. */
    private Long deckId;

    /** Question text for the front of the flashcard. */
    private String question;

    /** Answer text for the back of the flashcard. */
    private String answer;
  }

  /**
   * Request body for {@link #updateFlashcard}.
   * Both fields are optional; only non-null values are applied to the existing flashcard.
   */
  @Data
  static class FlashcardUpdateRequest {

    /** Replacement question text, or {@code null} to leave the existing question unchanged. */
    private String question;

    /** Replacement answer text, or {@code null} to leave the existing answer unchanged. */
    private String answer;
  }

  /**
   * Request body for {@link #attachMedia}.
   */
  @Data
  static class AttachMediaRequest {

    /** Side of the flashcard to attach media to; must be {@code "question"} or {@code "answer"}. */
    private String side;

    /** S3 object key of the uploaded media file. */
    private String s3ObjectKey;

    /** Original file name of the uploaded media, stored in {@link MediaMetadata}. */
    private String fileName;
  }
}
