import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { getSharedDecks, ApiError } from "../../api";
import { useAuth } from "../context/useAuth";
import type { DeckSummary } from "../../api";
import "./DecksPage.css";

export default function SharedDecksPage() {
  const [decks, setDecks] = useState<DeckSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();
  const auth = useAuth();

  useEffect(() => {
    if (auth.loading) return;
    if (!auth.isAuthenticated) {
      navigate("/login");
      return;
    }

    let cancelled = false;

    async function fetchDecks() {
      try {
        const data = await getSharedDecks();
        if (!cancelled) setDecks(data);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          auth.logoutSuccess();
          navigate("/login");
          return;
        }
        setError("Failed to load shared decks.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    fetchDecks();
    return () => {
      cancelled = true;
    };
  }, [navigate, auth]);

  if (auth.loading || (!auth.isAuthenticated && loading)) {
    return null;
  }

  if (loading) {
    return (
      <div className="decks-page">
        <div className="decks-inner">
          <p className="decks-loading">Loading shared decks...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="decks-page">
        <div className="decks-inner">
          <p className="decks-error">{error}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="decks-page">
      <div className="decks-inner">
        <div className="decks-header">
          <h1 className="decks-heading">Shared Decks</h1>
          <p className="decks-sub">
            {decks.length === 0
              ? "Decks that have been shared with you."
              : `${decks.length} shared deck${decks.length !== 1 ? "s" : ""}`}
          </p>
        </div>

        {decks.length === 0 ? (
          <div className="decks-empty">
            <div className="decks-empty-icon">▦</div>
            <h2 className="decks-empty-heading">No shared decks yet</h2>
            <p className="decks-empty-sub">
              When someone shares a deck with you, it will appear here.
            </p>
          </div>
        ) : (
          <div className="decks-grid">
            {decks.map((deck) => (
              <div key={deck.id} className="deck-card">
                <h3 className="deck-card-name">{deck.name}</h3>
                <p className="deck-card-count">
                  {deck.flashcardCount} card
                  {deck.flashcardCount !== 1 ? "s" : ""}
                </p>
                <p className="deck-card-owner">
                  Shared by {deck.sharedByUsername}
                </p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
