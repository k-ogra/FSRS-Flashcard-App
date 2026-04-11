package com.kogura.FSRS_Flashcard_App.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.SharedDeck;
import com.kogura.FSRS_Flashcard_App.model.User;

import java.util.List;

public interface SharedDeckRepository extends JpaRepository<SharedDeck, Long> {
  List<SharedDeck> findByUser(User user);
  List<SharedDeck> findByDeck(Deck deck);
  boolean existsByDeckAndUser(Deck deck, User user);

  @Query("SELECT sd.deck.id FROM SharedDeck sd WHERE sd.deck.user = :owner GROUP BY sd.deck.id")
  List<Long> findDeckIdsWithSharesByOwner(@Param("owner") User owner);

  void deleteByDeckAndUser(Deck deck, User user);
  void deleteByUser(User user);
  void deleteBySharer(User sharer);
  void deleteByDeck(Deck deck);
}
