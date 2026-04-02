package com.kogura.FSRS_Flashcard_App.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Table for user settings.
 */
@Entity
@Table(name = "user_settings")
@Getter
@Setter
@NoArgsConstructor
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, columnDefinition = "integer default 20")
    private int reviewAheadMinutes = 20;

    @Column(nullable = false, columnDefinition = "integer default 20")
    private int dailyNewCardLimit = 20;

    @Column(nullable = false, columnDefinition = "integer default 200")
    private int dailyReviewLimit = 200;
}
