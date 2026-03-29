package com.kogura.FSRS_Flashcard_App.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.SharedDeck;
import com.kogura.FSRS_Flashcard_App.model.User;

import java.util.List;

public interface SharedDeckRepository extends JpaRepository<SharedDeck, Long> {
  List<SharedDeck> findByUser(User user);
  boolean existsByDeckAndUser(Deck deck, User user);
  void deleteByDeckAndUser(Deck deck, User user);
  void deleteByUser(User user);
  void deleteBySharer(User sharer);
  void deleteByDeck(Deck deck);
}
