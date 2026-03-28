package com.kogura.FSRS_Flashcard_App.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.time.Instant;


import org.hibernate.annotations.CreationTimestamp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.github.openspacedrepetition.State;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "flashcards")
public class Flashcard {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String question;
  private String answer;
  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  // FSRS-6 scheduling fields
  private Double stability;        
  private Double difficulty;       

  private State state;
  private Integer step;

  private Instant dueDate;
  private Instant lastReview;

  @Column(name = "deck_id", insertable = false, updatable = false)
  private Long deckId;
}
