package com.kogura.FSRS_Flashcard_App.repository;

import com.kogura.FSRS_Flashcard_App.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
