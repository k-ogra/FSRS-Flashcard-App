package com.kogura.FSRS_Flashcard_App.model;

import java.time.LocalDate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;




/**
 * Table for daily study progress. Limits the number of new and review cards a user can study each day.
 */
@Entity
@Table(name = "daily_study_progress",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "deck_id"}))
@Getter
@Setter
@NoArgsConstructor
public class DailyStudyProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deck_id", nullable = false)
    private Deck deck;

    @Column(name = "study_date", nullable = false)
    private LocalDate studyDate;

    @Column(nullable = false)
    private int newCardsStudied = 0;

    @Column(nullable = false)
    private int reviewCardsStudied = 0;
}
