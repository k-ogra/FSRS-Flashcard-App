package com.kogura.FSRS_Flashcard_App.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.model.UserSettings;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {
  Optional<UserSettings> findByUser(User user);
  void deleteByUser(User user);
}
