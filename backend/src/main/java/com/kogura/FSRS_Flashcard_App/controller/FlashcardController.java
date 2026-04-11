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

@RestController
@RequestMapping("/api/v0/flashcards")
public class FlashcardController {
  private final FlashcardRepository flashcardRepository;
  private final DeckRepository deckRepository;
  private final UserRepository userRepository;
  private final S3Service s3Service;
  private final MediaMetadataService mediaMetadataService;

  @Autowired
  public FlashcardController(FlashcardRepository flashcardRepository, DeckRepository deckRepository,
      UserRepository userRepository, S3Service s3Service, MediaMetadataService mediaMetadataService) {
    this.flashcardRepository = flashcardRepository;
    this.deckRepository = deckRepository;
    this.userRepository = userRepository;
    this.s3Service = s3Service;
    this.mediaMetadataService = mediaMetadataService;
  }

  private User getAuthenticatedUser() {
    String username = SecurityContextHolder.getContext().getAuthentication().getName();
    return userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));
  }

  @GetMapping
  public List<Flashcard> getAllFlashcards() {
    List<Flashcard> flashcards = flashcardRepository.findAll();
    return flashcards;
  }

  @GetMapping("/{id}")
  public ResponseEntity<Flashcard> getFlashcardById(@PathVariable Long id) {
    Optional<Flashcard> flashcard = flashcardRepository.findById(id);
    if (flashcard.isPresent()) {
      return ResponseEntity.ok(flashcard.get());
    }
    return ResponseEntity.notFound().build();
  }

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
   * Attach media to a flashcard. Creates a new MediaMetadata entity with the presigned download URL. 
   * @param id The ID of the flashcard to attach media to.
   * @param request The request body containing the side of the flashcard to attach media to, the S3 object key, and the file name.
   * @return The attached flashcard or BAD_REQUEST if the request is invalid or NOT_FOUND if the flashcard is not found.
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

  @Data
  static class FlashcardCreateRequest {
    private Long deckId;
    private String question;
    private String answer;
  }

  @Data
  static class FlashcardUpdateRequest {
    private String question;
    private String answer;
  }

  @Data
  static class AttachMediaRequest {
    private String side;
    private String s3ObjectKey;
    private String fileName;
  }
}
