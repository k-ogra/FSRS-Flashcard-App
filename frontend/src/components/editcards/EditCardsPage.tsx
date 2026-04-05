import { useState, useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { getDeck, deleteFlashcard, ApiError } from "../../api";
import { useAuth } from "../context/useAuth";
import type { Deck } from "../../api";
import MediaRenderer from "../shared/MediaRenderer";
import EditCardModal from "./EditCardModal";
import "./EditCardsPage.css";

export default function EditCardsPage() {
  const { id } = useParams<{ id: string }>();
  const deckId = Number(id);
  const navigate = useNavigate();
  const auth = useAuth();

  const [deck, setDeck] = useState<Deck | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editTarget, setEditTarget] = useState<Deck["flashcards"][0] | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<{ id: number; question: string } | null>(null);
  const [deleting, setDeleting] = useState(false);

  async function fetchDeck() {
    try {
      const data = await getDeck(deckId);
      setDeck(data);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        auth.logoutSuccess();
        navigate("/login");
        return;
      }
      setError("Failed to load deck.");
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
    fetchDeck();
  }, [deckId, auth.loading, auth.isAuthenticated]);

  async function handleDeleteCard() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteFlashcard(deleteTarget.id);
      setDeck((prev) =>
        prev
          ? { ...prev, flashcards: prev.flashcards.filter((c) => c.id !== deleteTarget.id) }
          : prev,
      );
      setDeleteTarget(null);
    } catch {
      // silently fail — user can retry
    } finally {
      setDeleting(false);
    }
  }

  if (auth.loading) return null;

  if (loading) {
    return (
      <div className="edit-cards-page">
        <div className="edit-cards-inner">
          <p className="edit-cards-loading">Loading deck...</p>
        </div>
      </div>
    );
  }

  if (error || !deck) {
    return (
      <div className="edit-cards-page">
        <div className="edit-cards-inner">
          <p className="edit-cards-error">{error ?? "Deck not found."}</p>
        </div>
      </div>
    );
  }

  const cards = deck.flashcards ?? [];

  return (
    <div className="edit-cards-page">
      <div className="edit-cards-inner">
        <div className="edit-cards-header">
          <button className="edit-cards-back-btn" onClick={() => navigate("/my-decks")}>
            &larr; Back to Decks
          </button>
          <h1 className="edit-cards-deck-name">{deck.name}</h1>
          <p className="edit-cards-card-count">
            {cards.length} card{cards.length !== 1 ? "s" : ""}
          </p>
        </div>

        {cards.length === 0 ? (
          <div className="edit-cards-empty">
            <div className="edit-cards-empty-icon">&#9638;</div>
            <h2 className="edit-cards-empty-heading">No cards yet</h2>
            <p className="edit-cards-empty-sub">
              This deck doesn't have any flashcards. Go to study mode to add cards.
            </p>
          </div>
        ) : (
          <div className="edit-cards-list">
            {cards.map((card, index) => (
              <div key={card.id} className="edit-card">
                <div className="edit-card-number">Card {index + 1}</div>
                <div className="edit-card-question">{card.question}</div>
                <MediaRenderer
                  url={card.questionMediaMetadata?.presignedDownloadUrl}
                  fileName={card.questionMediaMetadata?.name}
                />
                <div className="edit-card-divider" />
                <div className="edit-card-answer">{card.answer}</div>
                <MediaRenderer
                  url={card.answerMediaMetadata?.presignedDownloadUrl}
                  fileName={card.answerMediaMetadata?.name}
                />
                <div className="edit-card-actions">
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={() => setEditTarget(card)}
                  >
                    Edit
                  </button>
                  <button
                    className="btn btn-ghost btn-sm edit-card-delete-btn"
                    onClick={() => setDeleteTarget({ id: card.id, question: card.question })}
                  >
                    Delete
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Delete confirmation */}
        {deleteTarget && (
          <div className="confirm-overlay" onClick={() => !deleting && setDeleteTarget(null)}>
            <div className="confirm-modal" onClick={(e) => e.stopPropagation()}>
              <h2 className="confirm-title">Delete this card?</h2>
              <p className="confirm-body">
                "{deleteTarget.question}" will be permanently deleted along with any attached media.
              </p>
              <div className="confirm-actions">
                <button
                  className="btn btn-outline btn-sm"
                  onClick={() => setDeleteTarget(null)}
                  disabled={deleting}
                >
                  Cancel
                </button>
                <button
                  className="btn btn-sm confirm-delete-btn"
                  onClick={handleDeleteCard}
                  disabled={deleting}
                >
                  {deleting ? "Deleting..." : "Delete Card"}
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Edit modal */}
        {editTarget && (
          <EditCardModal
            card={editTarget}
            onClose={() => setEditTarget(null)}
            onSaved={() => {
              setEditTarget(null);
              fetchDeck();
            }}
          />
        )}
      </div>
    </div>
  );
}
