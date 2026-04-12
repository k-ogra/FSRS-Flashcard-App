import { useState, useEffect, useRef, useMemo } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
  getStudyQueue,
  submitReview,
  createFlashcard,
  getPresignedUploadData,
  uploadFileToS3,
  attachFlashcardMedia,
  validateMediaFile,
  getUserSettings,
  ApiError,
} from "../../api";
import type { FlashcardStudy, Grade } from "../../api";
import MediaRenderer from "../shared/MediaRenderer";
import EditCardModal from "../editcards/EditCardModal";
import { useAuth } from "../context/useAuth";
import type { Deck } from "../../api";
import "./StudyPage.css";

function startOfLocalDay(d: Date): Date {
  const copy = new Date(d);
  copy.setHours(0, 0, 0, 0);
  return copy;
}

function shuffle<T>(arr: T[]): T[] {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
}

// Group cards by local calendar day of their dueDate (null dueDate = today),
// sort day groups ascending, shuffle within each group, and concatenate.
function organizeQueue(cards: FlashcardStudy[]): FlashcardStudy[] {
  const todayKey = startOfLocalDay(new Date()).getTime();
  const groups = new Map<number, FlashcardStudy[]>();
  for (const card of cards) {
    const key = card.dueDate
      ? startOfLocalDay(new Date(card.dueDate)).getTime()
      : todayKey;
    const bucket = groups.get(key) ?? [];
    bucket.push(card);
    groups.set(key, bucket);
  }
  const sortedKeys = [...groups.keys()].sort((a, b) => a - b);
  const out: FlashcardStudy[] = [];
  for (const key of sortedKeys) {
    out.push(...shuffle(groups.get(key)!));
  }
  return out;
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

  const [queue, setQueue] = useState<FlashcardStudy[]>([]);
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
  const [editingCard, setEditingCard] = useState<Deck["flashcards"][0] | null>(null);
  const [questionFilePreviewUrl, setQuestionFilePreviewUrl] = useState<string | null>(null);
  const [answerFilePreviewUrl, setAnswerFilePreviewUrl] = useState<string | null>(null);
  const questionRef = useRef<HTMLInputElement>(null);
  const questionFileInputRef = useRef<HTMLInputElement>(null);
  const answerFileInputRef = useRef<HTMLInputElement>(null);

  // Create/revoke object URLs for local file previews
  useEffect(() => {
    if (!questionFile) {
      setQuestionFilePreviewUrl(null);
      return;
    }
    const url = URL.createObjectURL(questionFile);
    setQuestionFilePreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [questionFile]);

  useEffect(() => {
    if (!answerFile) {
      setAnswerFilePreviewUrl(null);
      return;
    }
    const url = URL.createObjectURL(answerFile);
    setAnswerFilePreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [answerFile]);

  async function fetchSession() {
    setLoading(true);
    setError(null);
    try {
      const settings = await getUserSettings();
      setReviewAheadMinutes(settings.reviewAheadMinutes);
      const cards = await getStudyQueue(deckId);
      setQueue(organizeQueue(cards));
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

  async function handleRate(grade: Grade) {
    const card = queue[0];
    if (!card || reviewing) return;
    setReviewing(true);
    try {
      const updated = await submitReview(deckId, card.id, grade);
      setStudied((s) => s + 1);

      // Re-insert if still due within the review-ahead window.
      // The updated card is appended and the whole queue is re-organized so
      // it lands in the correct day bucket and is reshuffled with its peers.
      const reviewAheadTime = reviewAheadMinutes * 60 * 1000;
      const isDue =
        updated.dueDate != null &&
        new Date(updated.dueDate).getTime() <= Date.now() + reviewAheadTime;
      setQueue((q) => {
        const remaining = q.slice(1);
        return organizeQueue(isDue ? [...remaining, updated] : remaining);
      });

      setShowAnswer(false);
    } catch {
      // Silently fail — user can retry
    } finally {
      setReviewing(false);
    }
  }

  // Detect completion
  useEffect(() => {
    if (!loading && !completed && studied > 0 && queue.length === 0) {
      setCompleted(true);
    }
  }, [queue, loading, completed, studied]);

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

      // Upload media files in parallel, then attach them sequentially.
      const questionUpload = questionFile
        ? (async () => {
            const postData = await getPresignedUploadData(created.id, questionFile.name, true);
            await uploadFileToS3(postData, questionFile);
            return { key: postData.fields.key, name: questionFile.name };
          })()
        : null;
      const answerUpload = answerFile
        ? (async () => {
            const postData = await getPresignedUploadData(created.id, answerFile.name, false);
            await uploadFileToS3(postData, answerFile);
            return { key: postData.fields.key, name: answerFile.name };
          })()
        : null;

      if (questionUpload) {
        const { key, name } = await questionUpload;
        await attachFlashcardMedia(created.id, "question", key, name);
      }
      if (answerUpload) {
        const { key, name } = await answerUpload;
        await attachFlashcardMedia(created.id, "answer", key, name);
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

  // Convert a FlashcardStudy into the shape EditCardModal expects
  function toEditCardData(card: FlashcardStudy): Deck["flashcards"][0] {
    return {
      id: card.id,
      question: card.question,
      answer: card.answer,
      createdAt: "",
      stability: null,
      difficulty: null,
      state: null,
      step: null,
      dueDate: null,
      lastReview: null,
      questionMediaMetadata: card.questionMediaUrl
        ? { s3Key: null, name: card.questionMediaName, presignedDownloadUrl: card.questionMediaUrl }
        : null,
      answerMediaMetadata: card.answerMediaUrl
        ? { s3Key: null, name: card.answerMediaName, presignedDownloadUrl: card.answerMediaUrl }
        : null,
    };
  }

  // Refresh queue after an edit without resetting session progress (studied count, completed state)
  async function refreshQueuesAfterEdit() {
    try {
      const cards = await getStudyQueue(deckId);
      setQueue(organizeQueue(cards));
      setShowAnswer(false);
    } catch {
      // fail silently — user can retry by rating the card
    }
  }

  const { newCount, learningCount, reviewCount } = useMemo(() => {
    let n = 0, l = 0, r = 0;
    for (const c of queue) {
      if (c.state === "NEW") n++;
      else if (c.state === "LEARNING") l++;
      else if (c.state === "REVIEW") r++;
    }
    return { newCount: n, learningCount: l, reviewCount: r };
  }, [queue]);

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

  const currentCard = queue[0] ?? null;
  const remaining = queue.length;
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
              <span className="study-stat-value">{newCount}</span>
              <span className="study-stat-label">New</span>
            </div>
            <div className="study-stat study-stat--learn">
              <span className="study-stat-value">{learningCount}</span>
              <span className="study-stat-label">Learn</span>
            </div>
            <div className="study-stat study-stat--due">
              <span className="study-stat-value">{reviewCount}</span>
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
                    ref={questionFileInputRef}
                    type="file"
                    accept=".jpg,.jpeg,.png,.gif,.webp,.mp3,.wav,.ogg"
                    className="study-add-file-input"
                    onChange={(e) => {
                      const file = e.target.files?.[0] ?? null;
                      if (file) {
                        const error = validateMediaFile(file);
                        if (error) {
                          setAddError(error);
                          e.target.value = "";
                          return;
                        }
                        setAddError(null);
                      }
                      setQuestionFile(file);
                    }}
                    disabled={adding}
                  />
                </label>
                {questionFile && (
                  <button type="button" className="study-add-file-clear" onClick={() => { setQuestionFile(null); if (questionFileInputRef.current) questionFileInputRef.current.value = ""; }} disabled={adding}>
                    &times;
                  </button>
                )}
              </div>
              {questionFile && questionFilePreviewUrl && (
                <div className="study-add-file-preview">
                  <MediaRenderer url={questionFilePreviewUrl} fileName={questionFile.name} />
                </div>
              )}
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
                    ref={answerFileInputRef}
                    type="file"
                    accept=".jpg,.jpeg,.png,.gif,.webp,.mp3,.wav,.ogg"
                    className="study-add-file-input"
                    onChange={(e) => {
                      const file = e.target.files?.[0] ?? null;
                      if (file) {
                        const error = validateMediaFile(file);
                        if (error) {
                          setAddError(error);
                          e.target.value = "";
                          return;
                        }
                        setAddError(null);
                      }
                      setAnswerFile(file);
                    }}
                    disabled={adding}
                  />
                </label>
                {answerFile && (
                  <button type="button" className="study-add-file-clear" onClick={() => { setAnswerFile(null); if (answerFileInputRef.current) answerFileInputRef.current.value = ""; }} disabled={adding}>
                    &times;
                  </button>
                )}
              </div>
              {answerFile && answerFilePreviewUrl && (
                <div className="study-add-file-preview">
                  <MediaRenderer url={answerFilePreviewUrl} fileName={answerFile.name} />
                </div>
              )}
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
            <div className="study-card-top">
              <p className="study-progress">{remaining} cards left</p>
              <button
                type="button"
                className="study-edit-btn"
                onClick={() => setEditingCard(toEditCardData(currentCard))}
              >
                Edit Card
              </button>
            </div>
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

      {editingCard && (
        <EditCardModal
          card={editingCard}
          onClose={() => setEditingCard(null)}
          onSaved={async () => {
            setEditingCard(null);
            await refreshQueuesAfterEdit();
          }}
        />
      )}
    </div>
  );
}
