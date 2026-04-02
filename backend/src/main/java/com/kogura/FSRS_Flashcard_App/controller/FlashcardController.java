package com.kogura.FSRS_Flashcard_App.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.repository.DeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.FlashcardRepository;


import lombok.Data;

@RestController
@RequestMapping("/api/v0/flashcards")
public class FlashcardController {
  private final FlashcardRepository flashcardRepository;
  private final DeckRepository deckRepository;

  @Autowired
  public FlashcardController(FlashcardRepository flashcardRepository, DeckRepository deckRepository) {
    this.flashcardRepository = flashcardRepository;
    this.deckRepository = deckRepository;
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
    if (request.getQuestion() != null) {
      flashcard.setQuestion(request.getQuestion());
    }
    if (request.getAnswer() != null) {
      flashcard.setAnswer(request.getAnswer());
    }

    Flashcard updated = flashcardRepository.save(flashcard);
    return ResponseEntity.ok(updated);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteFlashcard(@PathVariable Long id) {
    if (!flashcardRepository.existsById(id)) {
      return ResponseEntity.notFound().build();
    }
    flashcardRepository.deleteById(id);
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
}
