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
import com.kogura.FSRS_Flashcard_App.model.MediaMetadata;
import com.kogura.FSRS_Flashcard_App.model.User;
import com.kogura.FSRS_Flashcard_App.repository.DailyStudyProgressRepository;
import com.kogura.FSRS_Flashcard_App.repository.FlashcardRepository;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.CardAndReviewLog;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;

/**
 * Core study session service — manages flashcard queues (new, learning, review),
 * processes card reviews through the FSRS scheduling algorithm, and tracks daily
 * study progress to enforce per-deck limits.
 */
@Service
public class StudyService {

  /** Repository for flashcard CRUD and queue queries. */
  private final FlashcardRepository flashcardRepository;

  /** Repository for per-user, per-deck daily study progress tracking. */
  private final DailyStudyProgressRepository dailyStudyProgressRepository;

  /** FSRS spaced-repetition scheduler used to compute next review intervals. */
  private final Scheduler scheduler;

  /** Service for refreshing presigned S3 download URLs on media metadata. */
  private final MediaMetadataService mediaMetadataService;

  /**
   * Constructs the study service with required dependencies.
   *
   * @param flashcardRepository            flashcard data access
   * @param dailyStudyProgressRepository   daily progress data access
   * @param scheduler                      FSRS scheduling engine
   * @param mediaMetadataService           media URL refresh service
   */
  public StudyService(FlashcardRepository flashcardRepository,
      DailyStudyProgressRepository dailyStudyProgressRepository, Scheduler scheduler,
      MediaMetadataService mediaMetadataService) {
    this.flashcardRepository = flashcardRepository;
    this.dailyStudyProgressRepository = dailyStudyProgressRepository;
    this.scheduler = scheduler;
    this.mediaMetadataService = mediaMetadataService;
  }

  /**
   * Retrieves or creates the daily study progress record for the given user and deck.
   * If the existing record is from a previous day, its counters are reset and the date
   * is updated to today. Handles concurrent insert race conditions via a catch-and-retry
   * on {@link DataIntegrityViolationException}.
   *
   * @param user the authenticated user
   * @param deck the deck being studied
   * @return today's {@link DailyStudyProgress} record, never {@code null}
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

  /**
   * Converts a database {@link Flashcard} entity into an FSRS {@link Card} object
   * for use with the scheduler. Cards with a {@code null} state (never reviewed) are
   * treated as {@link State#LEARNING}.
   *
   * @param dbCard the flashcard entity from the database
   * @return an FSRS {@link Card} populated with the flashcard's scheduling fields
   */
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

  /**
   * Returns a human-readable state label for a flashcard: {@code "NEW"} if never reviewed,
   * {@code "LEARNING"} for learning/relearning states, or {@code "REVIEW"} for review state.
   *
   * @param card the flashcard to classify
   * @return the state label string
   */
  private String stateLabel(Flashcard card) {
    if (card.getLastReview() == null) return "NEW";
    State state = card.getState();
    if (state == State.LEARNING || state == State.RELEARNING) return "LEARNING";
    return "REVIEW";
  }

  /**
   * Builds a {@link FlashcardStudyDTO} from a flashcard by running the FSRS scheduler
   * for all four ratings (AGAIN, HARD, GOOD, EASY) to compute interval previews. Also
   * refreshes presigned download URLs for any attached media.
   *
   * @param card the flashcard entity to convert
   * @return a fully populated study DTO with interval previews and media URLs
   */
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

    String questionMediaUrl = null;
    String questionMediaName = null;
    String answerMediaUrl = null;
    String answerMediaName = null;

    MediaMetadata qMeta = card.getQuestionMediaMetadata();
    if (qMeta != null && qMeta.getS3Key() != null) {
      qMeta = mediaMetadataService.refreshDownloadUrlIfNeeded(qMeta);
      questionMediaUrl = qMeta.getPresignedDownloadUrl();
      questionMediaName = qMeta.getName();
    }

    MediaMetadata aMeta = card.getAnswerMediaMetadata();
    if (aMeta != null && aMeta.getS3Key() != null) {
      aMeta = mediaMetadataService.refreshDownloadUrlIfNeeded(aMeta);
      answerMediaUrl = aMeta.getPresignedDownloadUrl();
      answerMediaName = aMeta.getName();
    }

    return new FlashcardStudyDTO(card.getId(), card.getQuestion(), card.getAnswer(),
        stateLabel(card), card.getDueDate(), againInterval, hardInterval, goodInterval, easyInterval,
        questionMediaUrl, questionMediaName, answerMediaUrl, answerMediaName);
  }

  /**
   * Returns the new-card study queue for a deck, limited by the user's remaining daily
   * new-card allowance. Cards are sorted by due date (nulls last).
   *
   * @param deckId           the deck ID to fetch new cards from
   * @param user             the authenticated user (for daily progress tracking)
   * @param deck             the deck entity (for daily progress tracking)
   * @param dailyNewCardLimit the maximum number of new cards allowed per day
   * @return a list of {@link FlashcardStudyDTO} for unseen cards, capped by the daily limit
   */
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

  /**
   * Returns the learning/relearning study queue for a deck. Includes cards in
   * {@link State#LEARNING} or {@link State#RELEARNING} whose due date falls within
   * the ahead-time cutoff. This queue is not subject to daily limits.
   *
   * @param deckId       the deck ID to fetch learning cards from
   * @param aheadMinutes how far ahead (in minutes) to look for due cards
   * @return a list of {@link FlashcardStudyDTO} sorted by due date
   */
  public List<FlashcardStudyDTO> getLearningQueue(Long deckId, int aheadMinutes) {
    Instant cutoff = Instant.now().plus(Duration.ofMinutes(aheadMinutes));
    List<Flashcard> learningCards = flashcardRepository
        .findByDeckIdAndStateInAndDueDateLessThanEqual(deckId, List.of(State.LEARNING, State.RELEARNING), cutoff);
    return learningCards.stream()
        .map(this::buildStudyDTO)
        .sorted(Comparator.comparing(FlashcardStudyDTO::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  /**
   * Returns the review study queue for a deck, limited by the user's remaining daily
   * review allowance. Includes {@link State#REVIEW} cards due within the ahead-time cutoff.
   *
   * @param deckId           the deck ID to fetch review cards from
   * @param aheadMinutes     how far ahead (in minutes) to look for due cards
   * @param user             the authenticated user (for daily progress tracking)
   * @param deck             the deck entity (for daily progress tracking)
   * @param dailyReviewLimit the maximum number of review cards allowed per day
   * @return a list of {@link FlashcardStudyDTO} for due review cards, capped by the daily limit
   */
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

  /**
   * Processes a review for a single flashcard: runs the FSRS scheduler with the given
   * rating, persists the updated scheduling fields, and increments the daily progress
   * counter for NEW or REVIEW cards (LEARNING/RELEARNING cards are exempt from daily limits).
   *
   * @param flashcardId the ID of the flashcard being reviewed
   * @param grade       the user's rating (AGAIN, HARD, GOOD, or EASY)
   * @param user        the authenticated user (for daily progress tracking)
   * @param deck        the deck entity (for daily progress tracking)
   * @return a {@link FlashcardStudyDTO} reflecting the updated card state and next intervals
   * @throws RuntimeException if the flashcard is not found
   */
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

  /**
   * Returns aggregate study counts for a deck, capping new and review counts by the
   * user's remaining daily allowances. The learning count is returned uncapped.
   *
   * @param deckId           the deck ID to count cards for
   * @param aheadMinutes     how far ahead (in minutes) to look for due cards
   * @param user             the authenticated user (for daily progress tracking)
   * @param deck             the deck entity (for daily progress tracking)
   * @param dailyNewCardLimit the maximum number of new cards allowed per day
   * @param dailyReviewLimit  the maximum number of review cards allowed per day
   * @return a {@link DeckStatsDTO} with capped new/review counts and uncapped learning count
   */
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
