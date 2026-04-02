import { useState, useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { getSharedDecks, copyDeck, ApiError } from "../../api";
import { useAuth } from "../context/useAuth";
import type { DeckSummary } from "../../api";
import "./DecksPage.css";

export default function SharedDecksPage() {
  const [decks, setDecks] = useState<DeckSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();
  const auth = useAuth();

  const [copyTarget, setCopyTarget] = useState<DeckSummary | null>(null);
  const [copyName, setCopyName] = useState("");
  const [copyError, setCopyError] = useState<string | null>(null);
  const [copying, setCopying] = useState(false);
  const [copySuccess, setCopySuccess] = useState<string | null>(null);
  const copyInputRef = useRef<HTMLInputElement>(null);

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

  function openCopyModal(deck: DeckSummary) {
    setCopyTarget(deck);
    setCopyName(deck.name);
    setCopyError(null);
    setCopySuccess(null);
    setTimeout(() => copyInputRef.current?.focus(), 0);
  }

  function closeCopyModal() {
    setCopyTarget(null);
    setCopyName("");
    setCopyError(null);
    setCopySuccess(null);
  }

  async function handleCopySubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!copyTarget) return;
    const trimmed = copyName.trim();
    if (!trimmed) {
      setCopyError("Deck name cannot be empty.");
      return;
    }
    setCopyError(null);
    setCopying(true);
    try {
      await copyDeck(copyTarget.id, trimmed);
      setCopySuccess("Deck added to your collection!");
    } catch (err) {
      if (err instanceof ApiError) {
        setCopyError(err.message);
      } else {
        setCopyError("Failed to copy deck.");
      }
    } finally {
      setCopying(false);
    }
  }

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
                <div className="deck-card-actions">
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={() => navigate(`/decks/${deck.id}/preview`)}
                  >
                    Preview
                  </button>
                  <button
                    className="copy-deck-btn"
                    onClick={() => openCopyModal(deck)}
                  >
                    + Add to My Decks
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        {copyTarget && (
          <div className="modal-overlay" onClick={closeCopyModal}>
            <div className="modal-card" onClick={(e) => e.stopPropagation()}>
              <h2 className="modal-heading">Add to My Decks</h2>
              <form onSubmit={handleCopySubmit}>
                <label className="form-label" htmlFor="copy-deck-name">
                  Deck name
                </label>
                <input
                  ref={copyInputRef}
                  id="copy-deck-name"
                  className="form-input"
                  type="text"
                  value={copyName}
                  onChange={(e) => setCopyName(e.target.value)}
                  disabled={copying}
                />
                {copyError && (
                  <p className="form-error" style={{ marginTop: 8 }}>
                    {copyError}
                  </p>
                )}
                {copySuccess && (
                  <p className="form-success" style={{ marginTop: 8 }}>
                    {copySuccess}
                  </p>
                )}
                <div className="modal-actions">
                  <button
                    type="button"
                    className="btn btn-ghost"
                    onClick={closeCopyModal}
                    disabled={copying}
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="btn btn-primary"
                    disabled={copying}
                  >
                    {copying ? "Copying..." : "Add Deck"}
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
