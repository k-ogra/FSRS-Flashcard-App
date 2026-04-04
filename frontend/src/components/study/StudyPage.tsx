import { useState, useEffect, useRef } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
  getNewQueue,
  getLearningQueue,
  getReviewQueue,
  submitReview,
  createFlashcard,
  getPresignedUploadUrl,
  uploadFileToS3,
  getUserSettings,
  ApiError,
} from "../../api";
import type { FlashcardStudy, Grade } from "../../api";
import MediaRenderer from "../shared/MediaRenderer";
import { useAuth } from "../context/useAuth";
import "./StudyPage.css";

function insertSorted(
  queue: FlashcardStudy[],
  card: FlashcardStudy,
): FlashcardStudy[] {
  const newQueue = [...queue];
  const idx = newQueue.findIndex(
    (c) => c.dueDate && card.dueDate && c.dueDate > card.dueDate,
  );
  if (idx === -1) newQueue.push(card);
  else newQueue.splice(idx, 0, card);
  return newQueue;
}

function formatDuration(iso: string): string {
  const match = iso.match(
    /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/,
  );
  if (!match) return iso;
  const days = parseInt(match[1] ?? "0");
  const hours = parseInt(match[2] ?? "0");
  const minutes = parseInt(match[3] ?? "0");
  const totalMinutes = days * 24 * 60 + hours * 60 + minutes;
  if (totalMinutes < 1) return "<1m";
  if (totalMinutes < 60) return `${totalMinutes}m`;
  if (totalMinutes < 24 * 60) return `${Math.round(totalMinutes / 60)}h`;
  return `${Math.round(totalMinutes / (24 * 60))}d`;
}

export default function StudyPage() {
  const { id } = useParams<{ id: string }>();
  const deckId = Number(id);
  const navigate = useNavigate();
  const auth = useAuth();

  const [newQueue, setNewQueue] = useState<FlashcardStudy[]>([]);
  const [learningQueue, setLearningQueue] = useState<FlashcardStudy[]>([]);
  const [reviewQueue, setReviewQueue] = useState<FlashcardStudy[]>([]);
  const [reviewAheadMinutes, setReviewAheadMinutes] = useState(20);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showAnswer, setShowAnswer] = useState(false);
  const [reviewing, setReviewing] = useState(false);
  const [completed, setCompleted] = useState(false);
  const [studied, setStudied] = useState(0);

  // Add card form
  const [addFormOpen, setAddFormOpen] = useState(false);
  const [newQuestion, setNewQuestion] = useState("");
  const [newAnswer, setNewAnswer] = useState("");
  const [addError, setAddError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [questionFile, setQuestionFile] = useState<File | null>(null);
  const [answerFile, setAnswerFile] = useState<File | null>(null);
  const questionRef = useRef<HTMLInputElement>(null);

  async function fetchSession() {
    setLoading(true);
    setError(null);
    try {
      const settings = await getUserSettings();
      const ahead = settings.reviewAheadMinutes;
      setReviewAheadMinutes(ahead);
      const [newCards, learningCards, reviewCards] = await Promise.all([
        getNewQueue(deckId),
        getLearningQueue(deckId, ahead),
        getReviewQueue(deckId, ahead),
      ]);
      setNewQueue(newCards);
      setLearningQueue(learningCards);
      setReviewQueue(reviewCards);
      setShowAnswer(false);
      setCompleted(false);
      setStudied(0);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        auth.logoutSuccess();
        navigate("/login");
        return;
      }
      setError("Failed to load study session.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (auth.loading) return;
    if (!auth.isAuthenticated) {
      navigate("/login");
      return;
    }
    fetchSession();
  }, [deckId, auth.loading, auth.isAuthenticated]);

  // Priority: learning -> review -> new
  function getNextCard(): FlashcardStudy | null {
    if (learningQueue.length > 0) return learningQueue[0];
    if (reviewQueue.length > 0) return reviewQueue[0];
    if (newQueue.length > 0) return newQueue[0];
    return null;
  }

  function shiftCurrentCard() {
    const card = getNextCard();
    if (!card) return;
    if (card.state === "LEARNING") {
      setLearningQueue((q) => q.slice(1));
    } else if (card.state === "REVIEW") {
      setReviewQueue((q) => q.slice(1));
    } else {
      setNewQueue((q) => q.slice(1));
    }
  }

  async function handleRate(grade: Grade) {
    const card = getNextCard();
    if (!card || reviewing) return;
    setReviewing(true);
    try {
      const updated = await submitReview(deckId, card.id, grade);
      shiftCurrentCard();
      setStudied((s) => s + 1);

      // Re-insert if still due within the review-ahead window
      const reviewAheadTime = reviewAheadMinutes * 60 * 1000;
      const isDue =
        updated.dueDate &&
        new Date(updated.dueDate) <= new Date(Date.now() + reviewAheadTime);
      if (isDue) {
        if (updated.state === "LEARNING") {
          setLearningQueue((q) => insertSorted(q, updated));
        } else if (updated.state === "REVIEW") {
          setReviewQueue((q) => insertSorted(q, updated));
        } else {
          setNewQueue((q) => insertSorted(q, updated));
        }
      }

      setShowAnswer(false);
    } catch {
      // Silently fail — user can retry
    } finally {
      setReviewing(false);
    }
  }

  // Detect completion
  useEffect(() => {
    if (
      !loading &&
      !completed &&
      studied > 0 &&
      newQueue.length === 0 &&
      learningQueue.length === 0 &&
      reviewQueue.length === 0
    ) {
      setCompleted(true);
    }
  }, [newQueue, learningQueue, reviewQueue, loading, completed, studied]);

  async function handleAddCard(e: React.FormEvent) {
    e.preventDefault();
    const q = newQuestion.trim();
    const a = newAnswer.trim();
    if (!q || !a) {
      setAddError("Both question and answer are required.");
      return;
    }
    setAddError(null);
    setAdding(true);
    try {
      const created = await createFlashcard(deckId, q, a);

      // Upload media files if attached
      const uploads: Promise<void>[] = [];
      if (questionFile) {
        uploads.push(
          getPresignedUploadUrl(created.id, questionFile.name, true)
            .then((url) => uploadFileToS3(url, questionFile, created.id, true)),
        );
      }
      if (answerFile) {
        uploads.push(
          getPresignedUploadUrl(created.id, answerFile.name, false)
            .then((url) => uploadFileToS3(url, answerFile, created.id, false)),
        );
      }
      if (uploads.length > 0) {
        await Promise.all(uploads);
        // Brief delay for SQS processing
        await new Promise((r) => setTimeout(r, 3000));
      }

      setNewQuestion("");
      setNewAnswer("");
      setQuestionFile(null);
      setAnswerFile(null);
      setAddFormOpen(false);
      await fetchSession();
    } catch (err) {
      if (err instanceof ApiError) {
        setAddError(err.message);
      } else {
        setAddError("Failed to add card.");
      }
    } finally {
      setAdding(false);
    }
  }

  useEffect(() => {
    if (addFormOpen) {
      setTimeout(() => questionRef.current?.focus(), 0);
    }
  }, [addFormOpen]);

  if (auth.loading) return null;

  if (loading) {
    return (
      <div className="study-page">
        <div className="study-inner">
          <p className="study-loading">Loading study session...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="study-page">
        <div className="study-inner">
          <p className="study-error">{error}</p>
        </div>
      </div>
    );
  }

  const currentCard = getNextCard();
  const remaining = newQueue.length + learningQueue.length + reviewQueue.length;
  const noCardsToStudy = remaining === 0 && studied === 0;

  return (
    <div className="study-page">
      <div className="study-inner">
        {/* Header */}
        <div className="study-header">
          <div>
            <button
              className="study-back-btn"
              onClick={() => navigate("/my-decks")}
            >
              &larr; Back to Decks
            </button>
          </div>
          <div className="study-stats">
            <div className="study-stat study-stat--new">
              <span className="study-stat-value">{newQueue.length}</span>
              <span className="study-stat-label">New</span>
            </div>
            <div className="study-stat study-stat--learn">
              <span className="study-stat-value">{learningQueue.length}</span>
              <span className="study-stat-label">Learn</span>
            </div>
            <div className="study-stat study-stat--due">
              <span className="study-stat-value">{reviewQueue.length}</span>
              <span className="study-stat-label">Review</span>
            </div>
          </div>
        </div>

        {/* Add Card Toggle + Form */}
        {!addFormOpen ? (
          <div style={{ marginBottom: 24 }}>
            <button
              className="study-add-toggle"
              onClick={() => setAddFormOpen(true)}
            >
              + Add Card
            </button>
          </div>
        ) : (
          <form className="study-add-form" onSubmit={handleAddCard}>
            <h3>Add a Flashcard</h3>
            <div className="study-add-fields">
              <input
                ref={questionRef}
                className="study-add-input"
                type="text"
                placeholder="Question"
                value={newQuestion}
                onChange={(e) => setNewQuestion(e.target.value)}
                disabled={adding}
              />
              <div className="study-add-file">
                <label className="study-add-file-label">
                  {questionFile ? questionFile.name : "Attach image/audio to question"}
                  <input
                    type="file"
                    accept="image/*,audio/*"
                    className="study-add-file-input"
                    onChange={(e) => setQuestionFile(e.target.files?.[0] ?? null)}
                    disabled={adding}
                  />
                </label>
                {questionFile && (
                  <button type="button" className="study-add-file-clear" onClick={() => setQuestionFile(null)} disabled={adding}>
                    &times;
                  </button>
                )}
              </div>
              <input
                className="study-add-input"
                type="text"
                placeholder="Answer"
                value={newAnswer}
                onChange={(e) => setNewAnswer(e.target.value)}
                disabled={adding}
              />
              <div className="study-add-file">
                <label className="study-add-file-label">
                  {answerFile ? answerFile.name : "Attach image/audio to answer"}
                  <input
                    type="file"
                    accept="image/*,audio/*"
                    className="study-add-file-input"
                    onChange={(e) => setAnswerFile(e.target.files?.[0] ?? null)}
                    disabled={adding}
                  />
                </label>
                {answerFile && (
                  <button type="button" className="study-add-file-clear" onClick={() => setAnswerFile(null)} disabled={adding}>
                    &times;
                  </button>
                )}
              </div>
            </div>
            {addError && (
              <div className="form-error" style={{ marginTop: 8 }}>
                {addError}
              </div>
            )}
            <div className="study-add-actions">
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => {
                  setAddFormOpen(false);
                  setAddError(null);
                }}
                disabled={adding}
              >
                Cancel
              </button>
              <button
                type="submit"
                className="btn btn-primary btn-sm"
                disabled={adding}
              >
                {adding ? "Adding..." : "Add Card"}
              </button>
            </div>
          </form>
        )}

        {/* Study Content */}
        {noCardsToStudy && !completed ? (
          <div className="study-done">
            <div className="study-done-icon">&#10003;</div>
            <h2 className="study-done-heading">You're done for today!</h2>
            <p className="study-done-sub">
              No cards to study right now. Add some cards or come back later.
            </p>
            <button
              className="btn btn-primary"
              onClick={() => navigate("/my-decks")}
            >
              Back to Decks
            </button>
          </div>
        ) : completed ? (
          <div className="study-done">
            <div className="study-done-icon">&#127881;</div>
            <h2 className="study-done-heading">Session complete!</h2>
            <p className="study-done-sub">
              You've completed all due cards for now. Great work!
            </p>
            <div style={{ display: "flex", gap: 12, justifyContent: "center" }}>
              <button className="btn btn-primary" onClick={fetchSession}>
                Study Again
              </button>
              <button
                className="btn btn-outline"
                onClick={() => navigate("/my-decks")}
              >
                Back to Decks
              </button>
            </div>
          </div>
        ) : currentCard ? (
          <div className="study-card">
            <p className="study-progress">{remaining} cards left</p>
            <p className="study-question-text">{currentCard.question}</p>
            <MediaRenderer url={currentCard.questionMediaUrl} fileName={currentCard.questionMediaName} />

            {!showAnswer ? (
              <button
                className="study-show-btn"
                onClick={() => setShowAnswer(true)}
              >
                Show Answer
              </button>
            ) : (
              <>
                <div className="study-divider" />
                <p className="study-answer-text">{currentCard.answer}</p>
                <MediaRenderer url={currentCard.answerMediaUrl} fileName={currentCard.answerMediaName} />
                <div className="study-rating-buttons">
                  <button
                    className="study-rating-btn study-rating-btn--again"
                    onClick={() => handleRate("AGAIN")}
                    disabled={reviewing}
                  >
                    Again
                    <span className="study-rating-diff">
                      {formatDuration(currentCard.againInterval)}
                    </span>
                  </button>
                  <button
                    className="study-rating-btn study-rating-btn--hard"
                    onClick={() => handleRate("HARD")}
                    disabled={reviewing}
                  >
                    Hard
                    <span className="study-rating-diff">
                      {formatDuration(currentCard.hardInterval)}
                    </span>
                  </button>
                  <button
                    className="study-rating-btn study-rating-btn--good"
                    onClick={() => handleRate("GOOD")}
                    disabled={reviewing}
                  >
                    Good
                    <span className="study-rating-diff">
                      {formatDuration(currentCard.goodInterval)}
                    </span>
                  </button>
                  <button
                    className="study-rating-btn study-rating-btn--easy"
                    onClick={() => handleRate("EASY")}
                    disabled={reviewing}
                  >
                    Easy
                    <span className="study-rating-diff">
                      {formatDuration(currentCard.easyInterval)}
                    </span>
                  </button>
                </div>
              </>
            )}
          </div>
        ) : null}
      </div>
    </div>
  );
}
