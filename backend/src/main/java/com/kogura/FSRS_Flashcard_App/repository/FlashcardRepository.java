package com.kogura.FSRS_Flashcard_App.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kogura.FSRS_Flashcard_App.model.Flashcard;

public interface FlashcardRepository extends JpaRepository<Flashcard, Long> {
}
