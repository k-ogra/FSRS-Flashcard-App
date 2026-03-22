package com.kogura.FSRS_Flashcard_App.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.repository.DeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v0/decks")
public class DeckController {
  private final DeckRepository deckRepository;
  private final UserRepository userRepository;

  @Autowired
  public DeckController(DeckRepository deckRepository, UserRepository userRepository) {
    this.deckRepository = deckRepository;
    this.userRepository = userRepository;
  }

  private User getAuthenticatedUser() {
    String username = SecurityContextHolder.getContext().getAuthentication().getName();
    return userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));
  }

  @GetMapping
  public List<Deck> getAllDecks() {
    User user = getAuthenticatedUser();
    return deckRepository.findByUser(user);
  }

  @GetMapping("/{id}")
  public ResponseEntity<Deck> getDeckById(@PathVariable Long id) {
    User user = getAuthenticatedUser();
    Optional<Deck> deck = deckRepository.findById(id);
    if (deck.isEmpty() || !deck.get().getUser().getId().equals(user.getId())) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(deck.get());
  }

  @PostMapping
  public Deck createDeck(@RequestBody Deck deck) {
    User user = getAuthenticatedUser();
    deck.setUser(user);
    return deckRepository.save(deck);
  }

  @PutMapping("/{id}")
  public ResponseEntity<Deck> updateDeck(@PathVariable Long id, @RequestBody Deck deckDetails) {
    User user = getAuthenticatedUser();
    Optional<Deck> optionalDeck = deckRepository.findById(id);
    if (optionalDeck.isEmpty() || !optionalDeck.get().getUser().getId().equals(user.getId())) {
      return ResponseEntity.notFound().build();
    }

    Deck deck = optionalDeck.get();
    deck.setName(deckDetails.getName());
    deck.setFlashcards(deckDetails.getFlashcards());
    Deck updated = deckRepository.save(deck);
    return ResponseEntity.ok(updated);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteDeck(@PathVariable Long id) {
    User user = getAuthenticatedUser();
    Optional<Deck> optionalDeck = deckRepository.findById(id);
    if (optionalDeck.isEmpty() || !optionalDeck.get().getUser().getId().equals(user.getId())) {
      return ResponseEntity.notFound().build();
    }
    deckRepository.deleteById(id);
    return ResponseEntity.noContent().build();
  }
}