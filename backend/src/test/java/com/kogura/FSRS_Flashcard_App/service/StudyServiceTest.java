package com.kogura.FSRS_Flashcard_App.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.kogura.FSRS_Flashcard_App.dto.DeckStatsDTO;
import com.kogura.FSRS_Flashcard_App.dto.FlashcardStudyDTO;
import com.kogura.FSRS_Flashcard_App.model.DailyStudyProgress;
import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.model.MediaMetadata;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.repository.DailyStudyProgressRepository;
import com.kogura.FSRS_Flashcard_App.repository.FlashcardRepository;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.CardAndReviewLog;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.ReviewLog;
import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;

@ExtendWith(MockitoExtension.class)
public class StudyServiceTest {

  @Mock
  private FlashcardRepository flashcardRepository;

  @Mock
  private DailyStudyProgressRepository dailyStudyProgressRepository;

  @Mock
  private Scheduler scheduler;

  @Mock
  private MediaMetadataService mediaMetadataService;

  private StudyService studyService;

  private User user;
  private Deck deck;

  @BeforeEach
  void setUp() {
    studyService = new StudyService(flashcardRepository, dailyStudyProgressRepository,
        scheduler, mediaMetadataService);

    user = new User();
    user.setId(1L);
    user.setUsername("testuser");

    deck = new Deck();
    deck.setId(10L);
    deck.setName("Test Deck");
  }

  // ── Helper methods ─────────────────────────────────────────

  private Flashcard newFlashcard(Long id, String question, String answer) {
    Flashcard fc = new Flashcard();
    fc.setId(id);
    fc.setQuestion(question);
    fc.setAnswer(answer);
    // NEW card: lastReview is null, state is null
    return fc;
  }

  private Flashcard reviewedFlashcard(Long id, State state, Instant dueDate) {
    Flashcard fc = newFlashcard(id, "Q" + id, "A" + id);
    fc.setState(state);
    fc.setDueDate(dueDate);
    fc.setLastReview(Instant.now().minus(Duration.ofHours(1)));
    fc.setDifficulty(5.0);
    fc.setStability(1.0);
    fc.setStep(0);
    return fc;
  }

  private DailyStudyProgress todayProgress(int newStudied, int reviewStudied) {
    DailyStudyProgress p = new DailyStudyProgress();
    p.setId(1L);
    p.setUser(user);
    p.setDeck(deck);
    p.setStudyDate(LocalDate.now());
    p.setNewCardsStudied(newStudied);
    p.setReviewCardsStudied(reviewStudied);
    return p;
  }

  private void stubSchedulerForCard(Flashcard card) {
    Instant now = Instant.now();
    for (Rating rating : Rating.values()) {
      Card resultCard = Card.builder()
          .state(State.LEARNING)
          .due(now.plus(Duration.ofMinutes(1)))
          .lastReview(now)
          .difficulty(5.0)
          .stability(1.0)
          .step(0)
          .build();
      ReviewLog log = new ReviewLog(0, rating, now, null);
      when(scheduler.reviewCard(any(Card.class), eq(rating)))
          .thenReturn(new CardAndReviewLog(resultCard, log));
    }
  }

  /** Stubs empty returns for both the learning and review repo queries. */
  private void stubEmptyLearningAndReview() {
    when(flashcardRepository.findByDeckIdAndStateInAndDueDateLessThanEqual(
        eq(10L), eq(List.of(State.LEARNING, State.RELEARNING)), any(Instant.class)))
        .thenReturn(Collections.emptyList());
    when(flashcardRepository.findByDeckIdAndStateAndDueDateLessThanEqual(
        eq(10L), eq(State.REVIEW), any(Instant.class)))
        .thenReturn(Collections.emptyList());
  }

  /** Stubs an empty return for the new-card repo query. */
  private void stubEmptyNew() {
    when(flashcardRepository.findByDeckIdAndLastReviewIsNull(10L))
        .thenReturn(Collections.emptyList());
  }

  // ── getStudyQueue ───────────────────────────────────────────

  /**
   * Verifies that the new-card portion of the unified queue is limited by the remaining
   * daily allowance (daily limit minus cards already studied today).
   */
  @Test
  void getStudyQueue_newCards_limitedByDailyProgress() {
    Flashcard fc1 = newFlashcard(1L, "Q1", "A1");
    Flashcard fc2 = newFlashcard(2L, "Q2", "A2");
    Flashcard fc3 = newFlashcard(3L, "Q3", "A3");

    when(flashcardRepository.findByDeckIdAndLastReviewIsNull(10L))
        .thenReturn(List.of(fc1, fc2, fc3));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(1, 0)));
    stubEmptyLearningAndReview();
    stubSchedulerForCard(fc1);

    // dailyNewCardLimit = 2, already studied 1 → remaining = 1
    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 20, user, deck, 2, 200);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getId()).isEqualTo(1L);
  }

  /**
   * Verifies that when no cards exist in any queue, an empty list is returned.
   */
  @Test
  void getStudyQueue_noCards_returnsEmptyList() {
    stubEmptyNew();
    stubEmptyLearningAndReview();
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));

    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 20, user, deck, 20, 200);

    assertThat(result).isEmpty();
  }

  /**
   * Verifies that when the daily new-card limit has been fully consumed, no new cards
   * appear in the queue.
   */
  @Test
  void getStudyQueue_newLimitAlreadyReached_excludesNewCards() {
    Flashcard fc1 = newFlashcard(1L, "Q1", "A1");
    when(flashcardRepository.findByDeckIdAndLastReviewIsNull(10L))
        .thenReturn(List.of(fc1));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(5, 0)));
    stubEmptyLearningAndReview();

    // dailyNewCardLimit = 5, already studied 5 → remaining = 0
    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 20, user, deck, 5, 200);

    assertThat(result).isEmpty();
  }

  /**
   * Verifies that when no {@link DailyStudyProgress} record exists for the user/deck,
   * one is created and persisted with today's date.
   */
  @Test
  void getStudyQueue_createsProgressIfNotExists() {
    stubEmptyNew();
    stubEmptyLearningAndReview();
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.empty());
    when(dailyStudyProgressRepository.save(any(DailyStudyProgress.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    studyService.getStudyQueue(10L, 20, user, deck, 20, 200);

    ArgumentCaptor<DailyStudyProgress> captor = ArgumentCaptor.forClass(DailyStudyProgress.class);
    verify(dailyStudyProgressRepository).save(captor.capture());
    DailyStudyProgress saved = captor.getValue();
    assertThat(saved.getUser()).isEqualTo(user);
    assertThat(saved.getDeck()).isEqualTo(deck);
    assertThat(saved.getStudyDate()).isEqualTo(LocalDate.now());
  }

  /**
   * Verifies that a stale progress record (from a previous day) has its date reset to
   * today and its new/review counters zeroed out.
   */
  @Test
  void getStudyQueue_staleProgress_resetsCounters() {
    DailyStudyProgress staleProgress = todayProgress(3, 5);
    staleProgress.setStudyDate(LocalDate.now().minusDays(1));

    stubEmptyNew();
    stubEmptyLearningAndReview();
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(staleProgress));
    when(dailyStudyProgressRepository.save(any(DailyStudyProgress.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    studyService.getStudyQueue(10L, 20, user, deck, 20, 200);

    assertThat(staleProgress.getStudyDate()).isEqualTo(LocalDate.now());
    assertThat(staleProgress.getNewCardsStudied()).isZero();
    assertThat(staleProgress.getReviewCardsStudied()).isZero();
  }

  /**
   * Verifies that new cards in the unified queue have their state set to {@code "NEW"}.
   */
  @Test
  void getStudyQueue_newCards_haveStateNEW() {
    Flashcard fc = newFlashcard(1L, "Q1", "A1");

    when(flashcardRepository.findByDeckIdAndLastReviewIsNull(10L))
        .thenReturn(List.of(fc));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));
    stubEmptyLearningAndReview();
    stubSchedulerForCard(fc);

    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 20, user, deck, 20, 200);

    assertThat(result.get(0).getState()).isEqualTo("NEW");
  }

  /**
   * Verifies that learning and relearning cards due within the cutoff are included in the
   * queue and are not subject to daily caps (returned even when new/review limits are 0).
   */
  @Test
  void getStudyQueue_learningCards_returnedUncapped() {
    Instant now = Instant.now();
    Flashcard fc1 = reviewedFlashcard(1L, State.LEARNING, now.minus(Duration.ofMinutes(5)));
    Flashcard fc2 = reviewedFlashcard(2L, State.RELEARNING, now.minus(Duration.ofMinutes(2)));

    stubEmptyNew();
    when(flashcardRepository.findByDeckIdAndStateInAndDueDateLessThanEqual(
        eq(10L), eq(List.of(State.LEARNING, State.RELEARNING)), any(Instant.class)))
        .thenReturn(List.of(fc1, fc2));
    when(flashcardRepository.findByDeckIdAndStateAndDueDateLessThanEqual(
        eq(10L), eq(State.REVIEW), any(Instant.class)))
        .thenReturn(Collections.emptyList());
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));
    stubSchedulerForCard(fc1);

    // Both daily limits at 0 — learning cards must still appear
    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 30, user, deck, 0, 0);

    assertThat(result).hasSize(2);
  }

  /**
   * Verifies that learning/relearning cards have their state set to {@code "LEARNING"}.
   */
  @Test
  void getStudyQueue_learningCards_haveStateLEARNING() {
    Instant now = Instant.now();
    Flashcard fc = reviewedFlashcard(1L, State.LEARNING, now);

    stubEmptyNew();
    when(flashcardRepository.findByDeckIdAndStateInAndDueDateLessThanEqual(
        eq(10L), any(), any(Instant.class)))
        .thenReturn(List.of(fc));
    when(flashcardRepository.findByDeckIdAndStateAndDueDateLessThanEqual(
        eq(10L), eq(State.REVIEW), any(Instant.class)))
        .thenReturn(Collections.emptyList());
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));
    stubSchedulerForCard(fc);

    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 30, user, deck, 20, 200);

    assertThat(result.get(0).getState()).isEqualTo("LEARNING");
  }

  /**
   * Verifies that the review portion of the unified queue is limited by the remaining
   * daily review allowance (daily limit minus reviews already completed today).
   */
  @Test
  void getStudyQueue_reviewCards_limitedByDailyProgress() {
    Instant now = Instant.now();
    Flashcard fc1 = reviewedFlashcard(1L, State.REVIEW, now.minus(Duration.ofMinutes(5)));
    Flashcard fc2 = reviewedFlashcard(2L, State.REVIEW, now.minus(Duration.ofMinutes(2)));

    stubEmptyNew();
    when(flashcardRepository.findByDeckIdAndStateInAndDueDateLessThanEqual(
        eq(10L), eq(List.of(State.LEARNING, State.RELEARNING)), any(Instant.class)))
        .thenReturn(Collections.emptyList());
    when(flashcardRepository.findByDeckIdAndStateAndDueDateLessThanEqual(
        eq(10L), eq(State.REVIEW), any(Instant.class)))
        .thenReturn(List.of(fc1, fc2));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));
    stubSchedulerForCard(fc1);

    // dailyReviewLimit = 1, studied 0 → remaining = 1
    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 30, user, deck, 20, 1);

    assertThat(result).hasSize(1);
  }

  /**
   * Verifies that when the daily review limit has been fully consumed, no review cards
   * appear in the queue.
   */
  @Test
  void getStudyQueue_reviewLimitAlreadyReached_excludesReviewCards() {
    Instant now = Instant.now();
    Flashcard fc = reviewedFlashcard(1L, State.REVIEW, now);

    stubEmptyNew();
    when(flashcardRepository.findByDeckIdAndStateInAndDueDateLessThanEqual(
        eq(10L), eq(List.of(State.LEARNING, State.RELEARNING)), any(Instant.class)))
        .thenReturn(Collections.emptyList());
    when(flashcardRepository.findByDeckIdAndStateAndDueDateLessThanEqual(
        eq(10L), eq(State.REVIEW), any(Instant.class)))
        .thenReturn(List.of(fc));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 100)));

    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 30, user, deck, 20, 100);

    assertThat(result).isEmpty();
  }

  /**
   * Verifies that review-state cards have their state set to {@code "REVIEW"}.
   */
  @Test
  void getStudyQueue_reviewCards_haveStateREVIEW() {
    Instant now = Instant.now();
    Flashcard fc = reviewedFlashcard(1L, State.REVIEW, now);

    stubEmptyNew();
    when(flashcardRepository.findByDeckIdAndStateInAndDueDateLessThanEqual(
        eq(10L), eq(List.of(State.LEARNING, State.RELEARNING)), any(Instant.class)))
        .thenReturn(Collections.emptyList());
    when(flashcardRepository.findByDeckIdAndStateAndDueDateLessThanEqual(
        eq(10L), eq(State.REVIEW), any(Instant.class)))
        .thenReturn(List.of(fc));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));
    stubSchedulerForCard(fc);

    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 30, user, deck, 20, 200);

    assertThat(result.get(0).getState()).isEqualTo("REVIEW");
  }

  /**
   * Verifies that cards from all three sources (new, learning, review) are merged into
   * a single flat list.
   */
  @Test
  void getStudyQueue_mergesAllThreeQueues() {
    Instant now = Instant.now();
    Flashcard fcNew = newFlashcard(1L, "New Q", "New A");
    Flashcard fcLearn = reviewedFlashcard(2L, State.LEARNING, now.minus(Duration.ofMinutes(5)));
    Flashcard fcReview = reviewedFlashcard(3L, State.REVIEW, now.minus(Duration.ofDays(1)));

    when(flashcardRepository.findByDeckIdAndLastReviewIsNull(10L))
        .thenReturn(List.of(fcNew));
    when(flashcardRepository.findByDeckIdAndStateInAndDueDateLessThanEqual(
        eq(10L), eq(List.of(State.LEARNING, State.RELEARNING)), any(Instant.class)))
        .thenReturn(List.of(fcLearn));
    when(flashcardRepository.findByDeckIdAndStateAndDueDateLessThanEqual(
        eq(10L), eq(State.REVIEW), any(Instant.class)))
        .thenReturn(List.of(fcReview));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));
    stubSchedulerForCard(fcNew);

    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 30, user, deck, 20, 200);

    assertThat(result).hasSize(3);
    assertThat(result.stream().map(FlashcardStudyDTO::getId))
        .containsExactlyInAnyOrder(1L, 2L, 3L);
  }

  // ── reviewCard ─────────────────────────────────────────────

  /**
   * Verifies that reviewing a NEW card increments the daily new-cards-studied counter
   * and persists the updated progress.
   */
  @Test
  void reviewCard_newCard_incrementsNewCardsStudied() {
    Flashcard fc = newFlashcard(1L, "Q1", "A1");
    DailyStudyProgress progress = todayProgress(0, 0);

    when(flashcardRepository.findById(1L)).thenReturn(Optional.of(fc));
    when(flashcardRepository.save(any(Flashcard.class))).thenAnswer(inv -> inv.getArgument(0));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(progress));

    stubSchedulerForCard(fc);

    studyService.reviewCard(1L, Rating.GOOD, user, deck);

    assertThat(progress.getNewCardsStudied()).isEqualTo(1);
    verify(dailyStudyProgressRepository, times(1)).save(progress);
  }

  /**
   * Verifies that reviewing a REVIEW-state card increments the daily review-cards-studied
   * counter and persists the updated progress.
   */
  @Test
  void reviewCard_reviewCard_incrementsReviewCardsStudied() {
    Flashcard fc = reviewedFlashcard(1L, State.REVIEW, Instant.now());
    DailyStudyProgress progress = todayProgress(0, 0);

    when(flashcardRepository.findById(1L)).thenReturn(Optional.of(fc));
    when(flashcardRepository.save(any(Flashcard.class))).thenAnswer(inv -> inv.getArgument(0));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(progress));

    stubSchedulerForCard(fc);

    studyService.reviewCard(1L, Rating.HARD, user, deck);

    assertThat(progress.getReviewCardsStudied()).isEqualTo(1);
    verify(dailyStudyProgressRepository, times(1)).save(progress);
  }

  /**
   * Verifies that reviewing a LEARNING-state card does not increment any daily progress
   * counter (learning/relearning reviews are uncapped).
   */
  @Test
  void reviewCard_learningCard_doesNotIncrementDailyProgress() {
    Flashcard fc = reviewedFlashcard(1L, State.LEARNING, Instant.now());

    when(flashcardRepository.findById(1L)).thenReturn(Optional.of(fc));
    when(flashcardRepository.save(any(Flashcard.class))).thenAnswer(inv -> inv.getArgument(0));

    stubSchedulerForCard(fc);

    studyService.reviewCard(1L, Rating.GOOD, user, deck);

    verify(dailyStudyProgressRepository, never()).save(any(DailyStudyProgress.class));
  }

  /**
   * Verifies that after a review, the flashcard's scheduling fields (due date, last review,
   * difficulty, stability, state) are updated to match the FSRS scheduler output.
   */
  @Test
  void reviewCard_updatesFlashcardSchedulingFields() {
    Flashcard fc = newFlashcard(1L, "Q1", "A1");
    Instant now = Instant.now();
    Instant expectedDue = now.plus(Duration.ofDays(1));

    Card scheduledCard = Card.builder()
        .state(State.REVIEW)
        .due(expectedDue)
        .lastReview(now)
        .difficulty(6.5)
        .stability(2.3)
        .step(0)
        .build();
    ReviewLog log = new ReviewLog(0, Rating.GOOD, now, null);
    when(scheduler.reviewCard(any(Card.class), eq(Rating.GOOD)))
        .thenReturn(new CardAndReviewLog(scheduledCard, log));
    // Stub other ratings for buildStudyDTO
    for (Rating r : new Rating[]{Rating.AGAIN, Rating.HARD, Rating.EASY}) {
      Card c = Card.builder().state(State.LEARNING).due(now.plusSeconds(60))
          .lastReview(now).difficulty(5.0).stability(1.0).step(0).build();
      ReviewLog l = new ReviewLog(0, r, now, null);
      when(scheduler.reviewCard(any(Card.class), eq(r)))
          .thenReturn(new CardAndReviewLog(c, l));
    }

    when(flashcardRepository.findById(1L)).thenReturn(Optional.of(fc));
    when(flashcardRepository.save(any(Flashcard.class))).thenAnswer(inv -> inv.getArgument(0));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));

    studyService.reviewCard(1L, Rating.GOOD, user, deck);

    assertThat(fc.getDueDate()).isEqualTo(expectedDue);
    assertThat(fc.getLastReview()).isEqualTo(now);
    assertThat(fc.getDifficulty()).isEqualTo(6.5);
    assertThat(fc.getStability()).isEqualTo(2.3);
    assertThat(fc.getState()).isEqualTo(State.REVIEW);
  }

  /**
   * Verifies that reviewing a non-existent flashcard throws a {@link RuntimeException}
   * with a "Flashcard not found" message.
   */
  @Test
  void reviewCard_flashcardNotFound_throws() {
    when(flashcardRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> studyService.reviewCard(99L, Rating.GOOD, user, deck))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Flashcard not found");
  }

  /**
   * Verifies that {@code reviewCard} returns a fully populated {@link FlashcardStudyDTO}
   * with the card's ID, question, answer, and non-null interval previews for all ratings.
   */
  @Test
  void reviewCard_returnsDTO() {
    Flashcard fc = newFlashcard(1L, "Q1", "A1");

    when(flashcardRepository.findById(1L)).thenReturn(Optional.of(fc));
    when(flashcardRepository.save(any(Flashcard.class))).thenAnswer(inv -> inv.getArgument(0));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));

    stubSchedulerForCard(fc);

    FlashcardStudyDTO dto = studyService.reviewCard(1L, Rating.GOOD, user, deck);

    assertThat(dto).isNotNull();
    assertThat(dto.getId()).isEqualTo(1L);
    assertThat(dto.getQuestion()).isEqualTo("Q1");
    assertThat(dto.getAnswer()).isEqualTo("A1");
    assertThat(dto.getAgainInterval()).isNotNull();
    assertThat(dto.getHardInterval()).isNotNull();
    assertThat(dto.getGoodInterval()).isNotNull();
    assertThat(dto.getEasyInterval()).isNotNull();
  }

  // ── getDeckStudyCounts ─────────────────────────────────────

  /**
   * Verifies that deck study counts cap new and review counts by the remaining daily
   * allowance, while learning count is returned uncapped.
   */
  @Test
  void getDeckStudyCounts_returnsCappedCounts() {
    when(flashcardRepository.countByDeckIdAndLastReviewIsNull(10L)).thenReturn(15);
    when(flashcardRepository.countByDeckIdAndStateInAndDueDateLessThanEqual(
        eq(10L), eq(List.of(State.LEARNING, State.RELEARNING)), any(Instant.class)))
        .thenReturn(3);
    when(flashcardRepository.countByDeckIdAndStateAndDueDateLessThanEqual(
        eq(10L), eq(State.REVIEW), any(Instant.class)))
        .thenReturn(25);
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(5, 10)));

    // newLimit=10, studied 5 → remaining 5; reviewLimit=20, studied 10 → remaining 10
    DeckStatsDTO stats = studyService.getDeckStudyCounts(10L, 30, user, deck, 10, 20);

    assertThat(stats.getNewCount()).isEqualTo(5);      // min(15, 5)
    assertThat(stats.getLearningCount()).isEqualTo(3);  // uncapped
    assertThat(stats.getReviewCount()).isEqualTo(10);   // min(25, 10)
  }

  /**
   * Verifies that when the deck has no cards in any queue, all counts are zero.
   */
  @Test
  void getDeckStudyCounts_zeroCards() {
    when(flashcardRepository.countByDeckIdAndLastReviewIsNull(10L)).thenReturn(0);
    when(flashcardRepository.countByDeckIdAndStateInAndDueDateLessThanEqual(
        eq(10L), any(), any(Instant.class))).thenReturn(0);
    when(flashcardRepository.countByDeckIdAndStateAndDueDateLessThanEqual(
        eq(10L), any(), any(Instant.class))).thenReturn(0);
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));

    DeckStatsDTO stats = studyService.getDeckStudyCounts(10L, 30, user, deck, 20, 200);

    assertThat(stats.getNewCount()).isZero();
    assertThat(stats.getLearningCount()).isZero();
    assertThat(stats.getReviewCount()).isZero();
  }

  /**
   * Verifies that when daily limits for new and review cards are fully consumed,
   * their counts are zero while the learning count remains unaffected.
   */
  @Test
  void getDeckStudyCounts_limitsAlreadyReached_returnsZeroForNewAndReview() {
    when(flashcardRepository.countByDeckIdAndLastReviewIsNull(10L)).thenReturn(10);
    when(flashcardRepository.countByDeckIdAndStateInAndDueDateLessThanEqual(
        eq(10L), any(), any(Instant.class))).thenReturn(2);
    when(flashcardRepository.countByDeckIdAndStateAndDueDateLessThanEqual(
        eq(10L), any(), any(Instant.class))).thenReturn(10);
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(20, 200)));

    DeckStatsDTO stats = studyService.getDeckStudyCounts(10L, 30, user, deck, 20, 200);

    assertThat(stats.getNewCount()).isZero();
    assertThat(stats.getLearningCount()).isEqualTo(2);
    assertThat(stats.getReviewCount()).isZero();
  }

  // ── buildStudyDTO media handling ───────────────────────────

  /**
   * Verifies that when a flashcard has media on both sides, the presigned download URLs
   * are refreshed via {@link MediaMetadataService} and set on the returned DTO.
   */
  @Test
  void buildStudyDTO_withMedia_refreshesAndSetsUrls() {
    Flashcard fc = newFlashcard(1L, "Q1", "A1");
    MediaMetadata qMeta = new MediaMetadata();
    qMeta.setS3Key("q-key.jpg");
    qMeta.setName("question.jpg");
    qMeta.setPresignedDownloadUrl("https://s3/q-url");
    fc.setQuestionMediaMetadata(qMeta);

    MediaMetadata aMeta = new MediaMetadata();
    aMeta.setS3Key("a-key.mp3");
    aMeta.setName("answer.mp3");
    aMeta.setPresignedDownloadUrl("https://s3/a-url");
    fc.setAnswerMediaMetadata(aMeta);

    when(mediaMetadataService.refreshDownloadUrlIfNeeded(qMeta)).thenReturn(qMeta);
    when(mediaMetadataService.refreshDownloadUrlIfNeeded(aMeta)).thenReturn(aMeta);
    when(flashcardRepository.findByDeckIdAndLastReviewIsNull(10L)).thenReturn(List.of(fc));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));
    stubEmptyLearningAndReview();
    stubSchedulerForCard(fc);

    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 20, user, deck, 20, 200);

    assertThat(result.get(0).getQuestionMediaUrl()).isEqualTo("https://s3/q-url");
    assertThat(result.get(0).getQuestionMediaName()).isEqualTo("question.jpg");
    assertThat(result.get(0).getAnswerMediaUrl()).isEqualTo("https://s3/a-url");
    assertThat(result.get(0).getAnswerMediaName()).isEqualTo("answer.mp3");
    verify(mediaMetadataService).refreshDownloadUrlIfNeeded(qMeta);
    verify(mediaMetadataService).refreshDownloadUrlIfNeeded(aMeta);
  }

  /**
   * Verifies that when a flashcard has no media metadata, the DTO's media URLs and names
   * are {@code null} and no refresh call is made.
   */
  @Test
  void buildStudyDTO_withoutMedia_urlsAreNull() {
    Flashcard fc = newFlashcard(1L, "Q1", "A1");

    when(flashcardRepository.findByDeckIdAndLastReviewIsNull(10L)).thenReturn(List.of(fc));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));
    stubEmptyLearningAndReview();
    stubSchedulerForCard(fc);

    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 20, user, deck, 20, 200);

    assertThat(result.get(0).getQuestionMediaUrl()).isNull();
    assertThat(result.get(0).getQuestionMediaName()).isNull();
    assertThat(result.get(0).getAnswerMediaUrl()).isNull();
    assertThat(result.get(0).getAnswerMediaName()).isNull();
    verify(mediaMetadataService, never()).refreshDownloadUrlIfNeeded(any());
  }

  /**
   * Verifies that when media metadata exists but has a {@code null} S3 key (e.g. orphaned
   * record), the refresh is skipped and the media URL on the DTO remains {@code null}.
   */
  @Test
  void buildStudyDTO_mediaWithNullS3Key_skipsRefresh() {
    Flashcard fc = newFlashcard(1L, "Q1", "A1");
    MediaMetadata qMeta = new MediaMetadata();
    qMeta.setS3Key(null); // no S3 key
    qMeta.setName("orphaned.jpg");
    fc.setQuestionMediaMetadata(qMeta);

    when(flashcardRepository.findByDeckIdAndLastReviewIsNull(10L)).thenReturn(List.of(fc));
    when(dailyStudyProgressRepository.findByUserAndDeck(user, deck))
        .thenReturn(Optional.of(todayProgress(0, 0)));
    stubEmptyLearningAndReview();
    stubSchedulerForCard(fc);

    List<FlashcardStudyDTO> result = studyService.getStudyQueue(10L, 20, user, deck, 20, 200);

    assertThat(result.get(0).getQuestionMediaUrl()).isNull();
    verify(mediaMetadataService, never()).refreshDownloadUrlIfNeeded(any());
  }
}
