package com.kogura.FSRS_Flashcard_App.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import com.kogura.FSRS_Flashcard_App.dto.DeckResponse;
import com.kogura.FSRS_Flashcard_App.dto.ShareRequest;
import com.kogura.FSRS_Flashcard_App.dto.VisibilityRequest;
import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.SharedDeck;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.repository.DeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.SharedDeckRepository;
import com.kogura.FSRS_Flashcard_App.repository.UserRepository;

import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;

@RestController
@RequestMapping("/api/v0/decks")
public class DeckController {
  private final DeckRepository deckRepository;
  private final SharedDeckRepository sharedDeckRepository;
  private final UserRepository userRepository;

  @Autowired
  public DeckController(DeckRepository deckRepository, SharedDeckRepository sharedDeckRepository, UserRepository userRepository) {
    this.deckRepository = deckRepository;
    this.sharedDeckRepository = sharedDeckRepository;
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

  @GetMapping("/public")
  public List<DeckResponse> getPublicDecks() {
    return deckRepository.findByIsPublicTrue().stream()
        .map(d -> DeckResponse.fromDeck(d, null))
        .collect(Collectors.toList());
  }

  @GetMapping("/shared")
  public List<DeckResponse> getSharedDecks() {
    User user = getAuthenticatedUser();
    return sharedDeckRepository.findByUser(user).stream()
        .map(sd -> DeckResponse.fromDeck(sd.getDeck(), sd.getSharer().getUsername()))
        .collect(Collectors.toList());
  }

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
    return ResponseEntity.ok(d);
  }

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
}