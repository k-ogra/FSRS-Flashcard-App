package com.kogura.FSRS_Flashcard_App.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.User;

import java.util.List;

public interface DeckRepository extends JpaRepository<Deck, Long> {
  List<Deck> findByUser(User user);
}
