import { useState, useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { getDeck, ApiError } from "../../api";
import { useAuth } from "../context/useAuth";
import type { Deck } from "../../api";
import "./PreviewPage.css";

export default function PreviewPage() {
  const { id } = useParams<{ id: string }>();
  const deckId = Number(id);
  const navigate = useNavigate();
  const auth = useAuth();

  const [deck, setDeck] = useState<Deck | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (auth.loading) return;
    if (!auth.isAuthenticated) {
      navigate("/login");
      return;
    }

    let cancelled = false;

    async function fetchDeck() {
      try {
        const data = await getDeck(deckId);
        if (!cancelled) setDeck(data);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          auth.logoutSuccess();
          navigate("/login");
          return;
        }
        setError("Failed to load deck.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    fetchDeck();
    return () => {
      cancelled = true;
    };
  }, [deckId, auth.loading, auth.isAuthenticated, auth, navigate]);

  if (auth.loading || (!auth.isAuthenticated && loading)) {
    return null;
  }

  if (loading) {
    return (
      <div className="preview-page">
        <div className="preview-inner">
          <p className="preview-loading">Loading deck...</p>
        </div>
      </div>
    );
  }

  if (error || !deck) {
    return (
      <div className="preview-page">
        <div className="preview-inner">
          <p className="preview-error">{error ?? "Deck not found."}</p>
        </div>
      </div>
    );
  }

  const cards = deck.flashcards ?? [];

  return (
    <div className="preview-page">
      <div className="preview-inner">
        <div className="preview-header">
          <button className="preview-back-btn" onClick={() => navigate(-1)}>
            ← Back
          </button>
          <h1 className="preview-deck-name">{deck.name}</h1>
          <p className="preview-card-count">
            {cards.length} card{cards.length !== 1 ? "s" : ""}
          </p>
        </div>

        {cards.length === 0 ? (
          <div className="preview-empty">
            <div className="preview-empty-icon">▦</div>
            <h2 className="preview-empty-heading">No cards yet</h2>
            <p className="preview-empty-sub">
              This deck doesn't have any flashcards.
            </p>
          </div>
        ) : (
          <div className="preview-list">
            {cards.map((card, index) => (
              <div key={card.id} className="preview-card">
                <div className="preview-card-number">Card {index + 1}</div>
                <div className="preview-card-question">{card.question}</div>
                <div className="preview-card-divider" />
                <div className="preview-card-answer">{card.answer}</div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
