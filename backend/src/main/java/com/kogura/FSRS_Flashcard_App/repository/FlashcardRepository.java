package com.kogura.FSRS_Flashcard_App.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kogura.FSRS_Flashcard_App.model.Flashcard;

import io.github.openspacedrepetition.State;

public interface FlashcardRepository extends JpaRepository<Flashcard, Long> {

  // NEW cards: never reviewed
  List<Flashcard> findByDeckIdAndLastReviewIsNull(Long deckId);
  int countByDeckIdAndLastReviewIsNull(Long deckId);

  // LEARNING cards: state is LEARNING or RELEARNING, and due
  List<Flashcard> findByDeckIdAndStateInAndDueDateLessThanEqual(Long deckId, List<State> states, Instant now);
  int countByDeckIdAndStateInAndDueDateLessThanEqual(Long deckId, List<State> states, Instant now);

  // REVIEW cards: state is REVIEW, and due
  List<Flashcard> findByDeckIdAndStateAndDueDateLessThanEqual(Long deckId, State state, Instant now);
  int countByDeckIdAndStateAndDueDateLessThanEqual(Long deckId, State state, Instant now);
}
