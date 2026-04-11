import { useState, useEffect, useRef } from "react";
import {
  shareDeck,
  unshareDeck,
  getDeckRecipients,
  ApiError,
} from "../../api";
import type { UserSummary } from "../../api";
import "../login/SignupLoginPage.css";

interface ShareDeckModalProps {
  isOpen: boolean;
  onClose: () => void;
  deckId: number;
  deckName: string;
  onRecipientsChanged?: () => void;
}

export default function ShareDeckModal({
  isOpen,
  onClose,
  deckId,
  deckName,
  onRecipientsChanged,
}: ShareDeckModalProps) {
  const [username, setUsername] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [recipients, setRecipients] = useState<UserSummary[]>([]);
  const [loadingRecipients, setLoadingRecipients] = useState(false);
  const [removingIds, setRemovingIds] = useState<Set<number>>(new Set());
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    setUsername("");
    setError(null);
    setSuccess(null);
    setSubmitting(false);
    setRecipients([]);
    setRemovingIds(new Set());
    setTimeout(() => inputRef.current?.focus(), 0);

    let cancelled = false;
    setLoadingRecipients(true);
    getDeckRecipients(deckId)
      .then((data) => {
        if (!cancelled) setRecipients(data);
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError) {
          setError(err.message);
        } else {
          setError("Failed to load recipients.");
        }
      })
      .finally(() => {
        if (!cancelled) setLoadingRecipients(false);
      });

    return () => {
      cancelled = true;
    };
  }, [isOpen, deckId]);

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
      const updated = await getDeckRecipients(deckId);
      setRecipients(updated);
      onRecipientsChanged?.();
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

  async function handleRemove(userId: number) {
    if (removingIds.has(userId)) return;
    setRemovingIds((prev) => {
      const next = new Set(prev);
      next.add(userId);
      return next;
    });
    setError(null);
    setSuccess(null);
    try {
      await unshareDeck(deckId, userId);
      setRecipients((prev) => prev.filter((u) => u.id !== userId));
      onRecipientsChanged?.();
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError("Failed to remove user.");
      }
    } finally {
      setRemovingIds((prev) => {
        const next = new Set(prev);
        next.delete(userId);
        return next;
      });
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()}>
        <h2 className="modal-heading">Share "{deckName}"</h2>

        <div className="share-recipients">
          <h3 className="share-recipients-heading">Shared with</h3>
          {loadingRecipients ? (
            <p className="share-recipients-empty">Loading...</p>
          ) : recipients.length === 0 ? (
            <p className="share-recipients-empty">
              Not shared with anyone yet.
            </p>
          ) : (
            <ul className="share-recipients-list">
              {recipients.map((u) => (
                <li key={u.id} className="share-recipient-row">
                  <span className="share-recipient-name">{u.username}</span>
                  <button
                    type="button"
                    className="share-recipient-remove"
                    onClick={() => handleRemove(u.id)}
                    disabled={removingIds.has(u.id)}
                    aria-label={`Remove ${u.username}`}
                  >
                    {removingIds.has(u.id) ? "..." : "Remove"}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

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
