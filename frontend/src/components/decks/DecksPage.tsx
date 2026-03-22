import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { getDecks, ApiError } from "../../api";
import { useAuth } from "../context/useAuth";
import type { Deck } from "../../api";
import "./DecksPage.css";

export default function DecksPage() {
  const [decks, setDecks] = useState<Deck[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();
  const auth = useAuth();

  useEffect(() => {
    // Wait for AuthContext to finish checking session
    if (auth.loading) return;

    // Redirect immediately if not authenticated
    if (!auth.isAuthenticated) {
      navigate("/login");
      return;
    }

    let cancelled = false;

    async function fetchDecks() {
      try {
        const data = await getDecks();
        if (!cancelled) setDecks(data);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          auth.logoutSuccess();
          navigate("/login");
          return;
        }
        setError("Failed to load decks.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    fetchDecks();
    return () => {
      cancelled = true;
    };
  }, [navigate, auth]);

  // Show nothing while auth state is being determined
  if (auth.loading || (!auth.isAuthenticated && loading)) {
    return null;
  }

  if (loading) {
    return (
      <div className="decks-page">
        <div className="decks-inner">
          <p className="decks-loading">Loading decks...</p>
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
          <h1 className="decks-heading">My Decks</h1>
          <p className="decks-sub">
            {decks.length === 0
              ? "You don't have any decks yet."
              : `${decks.length} deck${decks.length !== 1 ? "s" : ""}`}
          </p>
        </div>

        {decks.length === 0 ? (
          <div className="decks-empty">
            <div className="decks-empty-icon">▦</div>
            <h2 className="decks-empty-heading">No decks yet</h2>
            <p className="decks-empty-sub">
              Create your first deck to start studying.
            </p>
          </div>
        ) : (
          <div className="decks-grid">
            {decks.map((deck) => (
              <div key={deck.id} className="deck-card">
                <h3 className="deck-card-name">{deck.name}</h3>
                <p className="deck-card-count">
                  {deck.flashcards.length} card
                  {deck.flashcards.length !== 1 ? "s" : ""}
                </p>
                <p className="deck-card-date">
                  Created {new Date(deck.createdAt).toLocaleDateString()}
                </p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
