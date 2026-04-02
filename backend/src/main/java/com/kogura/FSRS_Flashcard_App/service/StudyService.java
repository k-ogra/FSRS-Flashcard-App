package com.kogura.FSRS_Flashcard_App.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.kogura.FSRS_Flashcard_App.dto.DeckStatsDTO;
import com.kogura.FSRS_Flashcard_App.dto.FlashcardStudyDTO;
import com.kogura.FSRS_Flashcard_App.model.DailyStudyProgress;
import com.kogura.FSRS_Flashcard_App.model.Deck;
import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.repository.DailyStudyProgressRepository;
import com.kogura.FSRS_Flashcard_App.repository.FlashcardRepository;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.CardAndReviewLog;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;

@Service
public class StudyService {

  private final FlashcardRepository flashcardRepository;
  private final DailyStudyProgressRepository dailyStudyProgressRepository;
  private final Scheduler scheduler;

  public StudyService(FlashcardRepository flashcardRepository,
      DailyStudyProgressRepository dailyStudyProgressRepository, Scheduler scheduler) {
    this.flashcardRepository = flashcardRepository;
    this.dailyStudyProgressRepository = dailyStudyProgressRepository;
    this.scheduler = scheduler;
  }

  /**
   * Get the daily study progress for a user and deck.
   * If the progress does not exist, create it.
   * If the progress is older than today, reset it.
   * @param user The user.
   * @param deck The deck.
   * @return The daily study progress.
   */
  private DailyStudyProgress getOrCreateTodayProgress(User user, Deck deck) {
    LocalDate today = LocalDate.now();
    DailyStudyProgress progress = dailyStudyProgressRepository.findByUserAndDeck(user, deck)
        .orElseGet(() -> {
          try {
            DailyStudyProgress p = new DailyStudyProgress();
            p.setUser(user);
            p.setDeck(deck);
            p.setStudyDate(today);
            return dailyStudyProgressRepository.save(p);
          } catch (DataIntegrityViolationException e) {
            // TODO: Might be a better way to organize the DB calls to avoid this race condition
            // Another concurrent request already inserted the row, return it 
            return dailyStudyProgressRepository.findByUserAndDeck(user, deck)
                .orElseThrow(() -> new RuntimeException("Failed to create daily study progress"));
          }
        });
    if (progress.getStudyDate().isBefore(today)) {
      progress.setStudyDate(today);
      progress.setNewCardsStudied(0);
      progress.setReviewCardsStudied(0);
      dailyStudyProgressRepository.save(progress);
    }
    return progress;
  }

  private Card constructFSRSCardFromDBCard(Flashcard dbCard) {
    return Card.builder()
      .difficulty(dbCard.getDifficulty())
      .lastReview(dbCard.getLastReview())
      .stability(dbCard.getStability())
      // Null should represent card that is NEW and hasn't ever been reviewed
      .state(dbCard.getState() == null ? State.LEARNING : dbCard.getState())
      .due(dbCard.getDueDate())
      .step(dbCard.getStep())
      .build();
  }

  private String stateLabel(Flashcard card) {
    if (card.getLastReview() == null) return "NEW";
    State state = card.getState();
    if (state == State.LEARNING || state == State.RELEARNING) return "LEARNING";
    return "REVIEW";
  }

  private FlashcardStudyDTO buildStudyDTO(Flashcard card) {
    Card fsrsCard = constructFSRSCardFromDBCard(card);
    CardAndReviewLog again = this.scheduler.reviewCard(fsrsCard, Rating.AGAIN);
    Duration againInterval = Duration.between(again.card().getLastReview(), again.card().getDue());
    CardAndReviewLog hard = this.scheduler.reviewCard(fsrsCard, Rating.HARD);
    Duration hardInterval = Duration.between(hard.card().getLastReview(), hard.card().getDue());
    CardAndReviewLog good = this.scheduler.reviewCard(fsrsCard, Rating.GOOD);
    Duration goodInterval = Duration.between(good.card().getLastReview(), good.card().getDue());
    CardAndReviewLog easy = this.scheduler.reviewCard(fsrsCard, Rating.EASY);
    Duration easyInterval = Duration.between(easy.card().getLastReview(), easy.card().getDue());

    return new FlashcardStudyDTO(card.getId(), card.getQuestion(), card.getAnswer(),
        stateLabel(card), card.getDueDate(), againInterval, hardInterval, goodInterval, easyInterval);
  }

  public List<FlashcardStudyDTO> getNewQueue(Long deckId, User user, Deck deck, int dailyNewCardLimit) {
    List<Flashcard> newCards = flashcardRepository.findByDeckIdAndLastReviewIsNull(deckId);
    DailyStudyProgress progress = getOrCreateTodayProgress(user, deck);
    int remaining = Math.max(0, dailyNewCardLimit - progress.getNewCardsStudied());
    return newCards.stream()
        .map(this::buildStudyDTO)
        .sorted(Comparator.comparing(FlashcardStudyDTO::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
        .limit(remaining)
        .toList();
  }

  public List<FlashcardStudyDTO> getLearningQueue(Long deckId, int aheadMinutes) {
    Instant cutoff = Instant.now().plus(Duration.ofMinutes(aheadMinutes));
    List<Flashcard> learningCards = flashcardRepository
        .findByDeckIdAndStateInAndDueDateLessThanEqual(deckId, List.of(State.LEARNING, State.RELEARNING), cutoff);
    return learningCards.stream()
        .map(this::buildStudyDTO)
        .sorted(Comparator.comparing(FlashcardStudyDTO::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  public List<FlashcardStudyDTO> getReviewQueue(Long deckId, int aheadMinutes, User user, Deck deck,
      int dailyReviewLimit) {
    Instant cutoff = Instant.now().plus(Duration.ofMinutes(aheadMinutes));
    List<Flashcard> reviewCards = flashcardRepository
        .findByDeckIdAndStateAndDueDateLessThanEqual(deckId, State.REVIEW, cutoff);
    DailyStudyProgress progress = getOrCreateTodayProgress(user, deck);
    int remaining = Math.max(0, dailyReviewLimit - progress.getReviewCardsStudied());
    return reviewCards.stream()
        .map(this::buildStudyDTO)
        .sorted(Comparator.comparing(FlashcardStudyDTO::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
        .limit(remaining)
        .toList();
  }

  public FlashcardStudyDTO reviewCard(Long flashcardId, Rating grade, User user, Deck deck) {
    Flashcard flashcard = flashcardRepository.findById(flashcardId)
        .orElseThrow(() -> new RuntimeException("Flashcard not found"));

    // Determine the card's category BEFORE the review for daily progress tracking
    boolean wasNew = flashcard.getLastReview() == null;
    boolean wasReview = !wasNew && flashcard.getState() == State.REVIEW;

    // Init FSRS library's Card object using data from DB
    Card fsrsCard = constructFSRSCardFromDBCard(flashcard);

    // Let FSRS library schedule the card's due date
    CardAndReviewLog result = this.scheduler.reviewCard(fsrsCard, grade);
    fsrsCard = result.card();

    // Update DB flashcard object
    flashcard.setDueDate(fsrsCard.getDue());
    flashcard.setDifficulty(fsrsCard.getDifficulty());
    flashcard.setStability(fsrsCard.getStability());
    flashcard.setStep(fsrsCard.getStep());
    flashcard.setLastReview(fsrsCard.getLastReview());
    flashcard.setState(fsrsCard.getState());

    Flashcard saved = flashcardRepository.save(flashcard);

    // Increment daily progress (learning/relearning cards are exempt)
    if (wasNew || wasReview) {
      DailyStudyProgress progress = getOrCreateTodayProgress(user, deck);
      if (wasNew) {
        progress.setNewCardsStudied(progress.getNewCardsStudied() + 1);
      } else {
        progress.setReviewCardsStudied(progress.getReviewCardsStudied() + 1);
      }
      dailyStudyProgressRepository.save(progress);
    }

    return buildStudyDTO(saved);
  }

  public DeckStatsDTO getDeckStudyCounts(Long deckId, int aheadMinutes, User user, Deck deck,
      int dailyNewCardLimit, int dailyReviewLimit) {
    Instant cutoff = Instant.now().plus(Duration.ofMinutes(aheadMinutes));
    int totalNew = flashcardRepository.countByDeckIdAndLastReviewIsNull(deckId);
    int learningCount = flashcardRepository.countByDeckIdAndStateInAndDueDateLessThanEqual(
        deckId, List.of(State.LEARNING, State.RELEARNING), cutoff);
    int totalReview = flashcardRepository.countByDeckIdAndStateAndDueDateLessThanEqual(
        deckId, State.REVIEW, cutoff);

    DailyStudyProgress progress = getOrCreateTodayProgress(user, deck);
    int newRemaining = Math.max(0, dailyNewCardLimit - progress.getNewCardsStudied());
    int reviewRemaining = Math.max(0, dailyReviewLimit - progress.getReviewCardsStudied());

    return new DeckStatsDTO(Math.min(totalNew, newRemaining), learningCount,
        Math.min(totalReview, reviewRemaining));
  }
}
