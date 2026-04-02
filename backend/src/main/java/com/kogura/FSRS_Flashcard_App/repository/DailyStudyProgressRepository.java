package com.kogura.FSRS_Flashcard_App.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kogura.FSRS_Flashcard_App.model.DailyStudyProgress;
import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.User;

public interface DailyStudyProgressRepository extends JpaRepository<DailyStudyProgress, Long> {
    Optional<DailyStudyProgress> findByUserAndDeck(User user, Deck deck);

    void deleteByUser(User user);

    void deleteByDeck(Deck deck);
}
