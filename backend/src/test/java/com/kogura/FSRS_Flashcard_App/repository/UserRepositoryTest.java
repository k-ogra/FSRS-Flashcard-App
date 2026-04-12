package com.kogura.FSRS_Flashcard_App.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.kogura.FSRS_Flashcard_App.model.User;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

  @Container
  @SuppressWarnings("resource")
  /**
   * PostgreSQL container for testing.
   * Warning suppressed because Testcontainers should close it. 
   */
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:latest")
      .withDatabaseName("testdb")
      .withUsername("test")
      .withPassword("test");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    // Prevent HikariCP from blocking 30 s trying to reconnect to the stopped
    // container during JVM shutdown (default connectionTimeout = 30 000 ms).
    registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
    // Do not maintain idle connections, so there is nothing to reconnect on shutdown.
    registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
  }

  @Autowired
  private UserRepository userRepository;

  // ── Helper ─────────────────────────────────────────────────

  private User buildUser(String username) {
    User user = new User();
    user.setUsername(username);
    user.setPassword("hashed-password");
    return user;
  }

  // ── save ───────────────────────────────────────────────────

  /**
   * Verifies that saving a new {@link User} causes Postgres to assign a
   * non-null auto-generated primary key via the {@code IDENTITY} strategy.
   */
  @Test
  void save_newUser_assignsGeneratedId() {
    User saved = userRepository.save(buildUser("alice"));
    assertThat(saved.getId()).isNotNull().isPositive();
  }

  /**
   * Verifies that all fields written to a {@link User} (username, password,
   * createdAt) survive a round-trip through the database.
   */
  @Test
  void save_newUser_persistsAllFields() {
    User saved = userRepository.save(buildUser("bob"));
    User found = userRepository.findById(saved.getId()).orElseThrow();
    assertThat(found.getUsername()).isEqualTo("bob");
    assertThat(found.getPassword()).isEqualTo("hashed-password");
    assertThat(found.getCreatedAt()).isNotNull();
  }

  /**
   * Verifies that persisting two users with the same username violates the
   * {@code UNIQUE} constraint and causes an exception to be thrown.
   */
  @Test
  void save_duplicateUsername_throwsException() {
    userRepository.saveAndFlush(buildUser("charlie"));
    assertThatThrownBy(() -> userRepository.saveAndFlush(buildUser("charlie")));
  }

  // ── findByUsername ─────────────────────────────────────────

  /**
   * Verifies that a previously persisted user can be retrieved by their
   * username.
   */
  @Test
  void findByUsername_existingUser_returnsUser() {
    userRepository.save(buildUser("diana"));
    Optional<User> result = userRepository.findByUsername("diana");
    assertThat(result).isPresent();
    assertThat(result.get().getUsername()).isEqualTo("diana");
  }

  /**
   * Verifies that querying for a username that was never saved returns an
   * empty {@link Optional}.
   */
  @Test
  void findByUsername_nonExistentUser_returnsEmpty() {
    Optional<User> result = userRepository.findByUsername("ghost");
    assertThat(result).isEmpty();
  }

  /**
   * Verifies that {@code findByUsername} is case-sensitive — "Eve" and "eve"
   * are distinct values in Postgres's default collation.
   */
  @Test
  void findByUsername_caseSensitive_returnsEmpty() {
    userRepository.save(buildUser("Eve"));
    Optional<User> result = userRepository.findByUsername("eve");
    assertThat(result).isEmpty();
  }

  // ── existsByUsername ───────────────────────────────────────

  /**
   * Verifies that {@code existsByUsername} returns {@code true} when a user
   * with that username has been persisted.
   */
  @Test
  void existsByUsername_existingUser_returnsTrue() {
    userRepository.save(buildUser("frank"));
    assertThat(userRepository.existsByUsername("frank")).isTrue();
  }

  /**
   * Verifies that {@code existsByUsername} returns {@code false} when no user
   * with that username exists.
   */
  @Test
  void existsByUsername_nonExistentUser_returnsFalse() {
    assertThat(userRepository.existsByUsername("nobody")).isFalse();
  }

  /**
   * Verifies that {@code existsByUsername} is case-sensitive — "Grace" is
   * persisted but "grace" should not be found.
   */
  @Test
  void existsByUsername_caseSensitive_returnsFalse() {
    userRepository.save(buildUser("Grace"));
    assertThat(userRepository.existsByUsername("grace")).isFalse();
  }
}
