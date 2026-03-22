import { useState, useEffect, useRef } from "react";
import { shareDeck, ApiError } from "../../api";
import "../login/SignupLoginPage.css";

interface ShareDeckModalProps {
  isOpen: boolean;
  onClose: () => void;
  deckId: number;
  deckName: string;
}

export default function ShareDeckModal({
  isOpen,
  onClose,
  deckId,
  deckName,
}: ShareDeckModalProps) {
  const [username, setUsername] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isOpen) {
      setUsername("");
      setError(null);
      setSuccess(null);
      setSubmitting(false);
      setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [isOpen]);

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
    const trimmed = username.trim();

    if (!trimmed) {
      setError("Username is required");
      return;
    }

    setError(null);
    setSuccess(null);
    setSubmitting(true);
    try {
      const result = await shareDeck(deckId, trimmed);
      setSuccess(result.message);
      setUsername("");
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
        <h2 className="modal-heading">Share "{deckName}"</h2>
        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label className="form-label" htmlFor="share-username-input">
              Username
            </label>
            <input
              id="share-username-input"
              ref={inputRef}
              className="form-input"
              type="text"
              placeholder="Enter username to share with"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              disabled={submitting}
            />
          </div>
          {error && <div className="form-error">{error}</div>}
          {success && <div className="form-success">{success}</div>}
          <div className="modal-actions">
            <button
              type="button"
              className="btn btn-ghost"
              onClick={onClose}
              disabled={submitting}
            >
              Close
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={submitting}
            >
              {submitting ? "Sharing..." : "Share"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
