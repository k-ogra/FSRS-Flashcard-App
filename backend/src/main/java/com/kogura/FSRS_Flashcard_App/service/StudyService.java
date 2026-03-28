package com.kogura.FSRS_Flashcard_App.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kogura.FSRS_Flashcard_App.dto.DeckStatsDTO;
import com.kogura.FSRS_Flashcard_App.dto.FlashcardStudyDTO;
import com.kogura.FSRS_Flashcard_App.model.Flashcard;
import com.kogura.FSRS_Flashcard_App.repository.FlashcardRepository;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.CardAndReviewLog;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;

@Service
public class StudyService {

  private final FlashcardRepository flashcardRepository;
  private final Scheduler scheduler;

  public StudyService(FlashcardRepository flashcardRepository, Scheduler scheduler) {
    this.flashcardRepository = flashcardRepository;
    this.scheduler = scheduler;
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

  public List<FlashcardStudyDTO> getNewQueue(Long deckId) {
    List<Flashcard> newCards = flashcardRepository.findByDeckIdAndLastReviewIsNull(deckId);
    return newCards.stream()
        .map(this::buildStudyDTO)
        .sorted(Comparator.comparing(FlashcardStudyDTO::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  public List<FlashcardStudyDTO> getLearningQueue(Long deckId) {
    Instant now = Instant.now();
    List<Flashcard> learningCards = flashcardRepository
        .findByDeckIdAndStateInAndDueDateLessThanEqual(deckId, List.of(State.LEARNING, State.RELEARNING), now);
    return learningCards.stream()
        .map(this::buildStudyDTO)
        .sorted(Comparator.comparing(FlashcardStudyDTO::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  public List<FlashcardStudyDTO> getReviewQueue(Long deckId) {
    Instant now = Instant.now();
    List<Flashcard> reviewCards = flashcardRepository
        .findByDeckIdAndStateAndDueDateLessThanEqual(deckId, State.REVIEW, now);
    return reviewCards.stream()
        .map(this::buildStudyDTO)
        .sorted(Comparator.comparing(FlashcardStudyDTO::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  public FlashcardStudyDTO reviewCard(Long flashcardId, Rating grade) {
    Flashcard flashcard = flashcardRepository.findById(flashcardId)
        .orElseThrow(() -> new RuntimeException("Flashcard not found"));

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
    return buildStudyDTO(saved);
  }

  public DeckStatsDTO getDeckStudyCounts(Long deckId) {
    Instant now = Instant.now();
    int newCount = flashcardRepository.countByDeckIdAndLastReviewIsNull(deckId);
    int learningCount = flashcardRepository.countByDeckIdAndStateInAndDueDateLessThanEqual(
        deckId, List.of(State.LEARNING, State.RELEARNING), now);
    int reviewCount = flashcardRepository.countByDeckIdAndStateAndDueDateLessThanEqual(
        deckId, State.REVIEW, now);
    return new DeckStatsDTO(newCount, learningCount, reviewCount);
  }
}
