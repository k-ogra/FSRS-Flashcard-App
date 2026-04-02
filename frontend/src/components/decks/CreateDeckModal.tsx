import { useState, useEffect, useRef } from "react";
import { createDeck, ApiError } from "../../api";
import type { Deck } from "../../api";
import "../login/SignupLoginPage.css";

interface CreateDeckModalProps {
  isOpen: boolean;
  onClose: () => void;
  onCreated: (deck: Deck) => void;
  existingDeckNames: string[];
}

export default function CreateDeckModal({
  isOpen,
  onClose,
  onCreated,
  existingDeckNames,
}: CreateDeckModalProps) {
  const [deckName, setDeckName] = useState("");
  const [isPublic, setIsPublic] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  // Reset state when modal opens
  useEffect(() => {
    if (isOpen) {
      setDeckName("");
      setIsPublic(false);
      setError(null);
      setSubmitting(false);
      // Auto-focus after a tick so the element is rendered
      setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [isOpen]);

  // Close on Escape
  useEffect(() => {
    if (!isOpen) return;
    function handleKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", handleKey);
    return () => document.removeEventListener("keydown", handleKey);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  async function handleSubmit(e: React.SubmitEvent) {
    e.preventDefault();
    const trimmed = deckName.trim();

    if (!trimmed) {
      setError("Deck name cannot be empty");
      return;
    }

    if (
      existingDeckNames.some(
        (n) => n.toLowerCase() === trimmed.toLowerCase()
      )
    ) {
      setError("A deck with this name already exists");
      return;
    }

    setError(null);
    setSubmitting(true);
    try {
      const newDeck = await createDeck(trimmed, isPublic);
      onCreated(newDeck);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError("Something went wrong. Please try again.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()}>
        <h2 className="modal-heading">Create New Deck</h2>
        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label className="form-label" htmlFor="deck-name-input">
              Deck name
            </label>
            <input
              id="deck-name-input"
              ref={inputRef}
              className="form-input"
              type="text"
              placeholder="e.g. Biology 101"
              value={deckName}
              onChange={(e) => setDeckName(e.target.value)}
              disabled={submitting}
            />
          </div>
          <div className="form-field" style={{ marginTop: 8 }}>
            <label className="toggle-row">
              <span className="form-label">Make Public</span>
              <span
                className={`toggle-track ${isPublic ? "toggle-track--on" : ""}`}
                role="switch"
                aria-checked={isPublic}
              >
                <span className="toggle-thumb" />
              </span>
              <input
                type="checkbox"
                className="toggle-hidden"
                checked={isPublic}
                onChange={(e) => setIsPublic(e.target.checked)}
                disabled={submitting}
              />
            </label>
          </div>
          {error && <div className="form-error">{error}</div>}
          <div className="modal-actions">
            <button
              type="button"
              className="btn btn-ghost"
              onClick={onClose}
              disabled={submitting}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={submitting}
            >
              {submitting ? "Creating..." : "Create"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
