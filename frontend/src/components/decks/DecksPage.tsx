import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { getDecks, toggleDeckVisibility, ApiError } from "../../api";
import { useAuth } from "../context/useAuth";
import type { Deck } from "../../api";
import CreateDeckModal from "./CreateDeckModal";
import ShareDeckModal from "./ShareDeckModal";
import "./DecksPage.css";

export default function DecksPage() {
  const [decks, setDecks] = useState<Deck[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [shareDeckTarget, setShareDeckTarget] = useState<{ id: number; name: string } | null>(null);
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
          <div className="decks-header-row">
            <div>
              <h1 className="decks-heading">My Decks</h1>
              <p className="decks-sub">
                {decks.length === 0
                  ? "You don't have any decks yet."
                  : `${decks.length} deck${decks.length !== 1 ? "s" : ""}`}
              </p>
            </div>
            <button
              className="btn btn-primary"
              onClick={() => setModalOpen(true)}
            >
              + Create Deck
            </button>
          </div>
        </div>

        {decks.length === 0 ? (
          <div className="decks-empty">
            <div className="decks-empty-icon">▦</div>
            <h2 className="decks-empty-heading">No decks yet</h2>
            <p className="decks-empty-sub">
              Create your first deck to start studying.
            </p>
            <button
              className="btn btn-primary decks-empty-cta"
              onClick={() => setModalOpen(true)}
            >
              + Create Deck
            </button>
          </div>
        ) : (
          <div className="decks-grid">
            {decks.map((deck) => (
              <div key={deck.id} className="deck-card">
                <h3 className="deck-card-name">{deck.name}</h3>
                <p className="deck-card-count">
                  {(deck.flashcards ?? []).length} card
                  {(deck.flashcards ?? []).length !== 1 ? "s" : ""}
                </p>
                <p className="deck-card-date">
                  Created {new Date(deck.createdAt).toLocaleDateString()}
                </p>
                <p className="deck-card-date">
                  {deck.isPublic ? "Public Deck" : "Private Deck"}
                </p>
                <div className="deck-card-actions">
                  <button
                    className={`visibility-badge ${deck.isPublic ? "visibility-badge--public" : ""}`}
                    onClick={async () => {
                      try {
                        const updated = await toggleDeckVisibility(deck.id, !deck.isPublic);
                        setDecks((prev) =>
                          prev.map((d) =>
                            d.id === deck.id ? { ...d, isPublic: updated.isPublic } : d
                          )
                        );
                      } catch {
                        // TODO: Add error msg? 
                        // silently fail — user can retry
                      }
                    }}
                  >
                    {deck.isPublic ? "Make Private" : "Make Public"}
                  </button>
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={() => setShareDeckTarget({ id: deck.id, name: deck.name })}
                  >
                    Share
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        <CreateDeckModal
          isOpen={modalOpen}
          onClose={() => setModalOpen(false)}
          onCreated={(newDeck) => {
            setDecks((prev) => [...prev, newDeck]);
            setModalOpen(false);
          }}
          existingDeckNames={decks.map((d) => d.name)}
        />

        <ShareDeckModal
          isOpen={shareDeckTarget !== null}
          onClose={() => setShareDeckTarget(null)}
          deckId={shareDeckTarget?.id ?? 0}
          deckName={shareDeckTarget?.name ?? ""}
        />
      </div>
    </div>
  );
}
