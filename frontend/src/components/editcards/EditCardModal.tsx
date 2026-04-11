import { useState, useEffect, useRef } from "react";
import {
  updateFlashcard,
  deleteFlashcardMedia,
  getPresignedUploadData,
  uploadFileToS3,
  validateMediaFile,
  ApiError,
  attachFlashcardMedia,
} from "../../api";
import type { Deck } from "../../api";
import MediaRenderer from "../shared/MediaRenderer";
import "./EditCardsPage.css";

type FlashcardData = Deck["flashcards"][0];
type MediaAction = "keep" | "remove" | "replace" | "add";

interface EditCardModalProps {
  card: FlashcardData;
  onClose: () => void;
  onSaved: () => void;
}

export default function EditCardModal({ card, onClose, onSaved }: EditCardModalProps) {
  const [question, setQuestion] = useState(card.question);
  const [answer, setAnswer] = useState(card.answer);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [qMediaAction, setQMediaAction] = useState<MediaAction>("keep");
  const [aMediaAction, setAMediaAction] = useState<MediaAction>("keep");
  const [newQFile, setNewQFile] = useState<File | null>(null);
  const [newAFile, setNewAFile] = useState<File | null>(null);
  const [newQPreviewUrl, setNewQPreviewUrl] = useState<string | null>(null);
  const [newAPreviewUrl, setNewAPreviewUrl] = useState<string | null>(null);

  const questionRef = useRef<HTMLInputElement>(null);

  // Create/revoke object URLs for local file previews
  useEffect(() => {
    if (!newQFile) {
      setNewQPreviewUrl(null);
      return;
    }
    const url = URL.createObjectURL(newQFile);
    setNewQPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [newQFile]);

  useEffect(() => {
    if (!newAFile) {
      setNewAPreviewUrl(null);
      return;
    }
    const url = URL.createObjectURL(newAFile);
    setNewAPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [newAFile]);

  // Focus question input on mount
  useEffect(() => {
    setTimeout(() => questionRef.current?.focus(), 0);
  }, []);

  // Close on Escape
  useEffect(() => {
    function handleKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", handleKey);
    return () => document.removeEventListener("keydown", handleKey);
  }, [onClose]);

  function handleFileSelect(
    file: File | null,
    side: "question" | "answer",
  ) {
    if (file) {
      const validationError = validateMediaFile(file);
      if (validationError) {
        setError(validationError);
        return;
      }
      setError(null);
    }

    if (side === "question") {
      setNewQFile(file);
      if (file) {
        setQMediaAction(card.questionMediaMetadata ? "replace" : "add");
      } else {
        setQMediaAction("keep");
      }
    } else {
      setNewAFile(file);
      if (file) {
        setAMediaAction(card.answerMediaMetadata ? "replace" : "add");
      } else {
        setAMediaAction("keep");
      }
    }
  }

  function handleRemove(side: "question" | "answer") {
    if (side === "question") {
      setQMediaAction("remove");
      setNewQFile(null);
    } else {
      setAMediaAction("remove");
      setNewAFile(null);
    }
  }

  function handleUndo(side: "question" | "answer") {
    if (side === "question") {
      setQMediaAction("keep");
      setNewQFile(null);
    } else {
      setAMediaAction("keep");
      setNewAFile(null);
    }
  }

  async function handleSave(e: React.FormEvent) {
    e.preventDefault();
    const q = question.trim();
    const a = answer.trim();
    if (!q || !a) {
      setError("Both question and answer are required.");
      return;
    }

    setError(null);
    setSaving(true);
    try {
      // 1. Update text
      await updateFlashcard(card.id, q, a);

      // Question media
      if (qMediaAction === "remove") {
        await deleteFlashcardMedia(card.id, "question");
      } else if (qMediaAction === "replace" && newQFile) {
        await deleteFlashcardMedia(card.id, "question");
        const postData = await getPresignedUploadData(card.id, newQFile.name, true);
        await uploadFileToS3(postData, newQFile);
        await attachFlashcardMedia(card.id, "question", postData.fields.key, newQFile.name);
      } else if (qMediaAction === "add" && newQFile) {
        const postData = await getPresignedUploadData(card.id, newQFile.name, true);
        await uploadFileToS3(postData, newQFile);
        await attachFlashcardMedia(card.id, "question", postData.fields.key, newQFile.name);
      }

      // Answer media
      if (aMediaAction === "remove") {
        await deleteFlashcardMedia(card.id, "answer");
      } else if (aMediaAction === "replace" && newAFile) {
        await deleteFlashcardMedia(card.id, "answer");
        const postData = await getPresignedUploadData(card.id, newAFile.name, false);
        await uploadFileToS3(postData, newAFile);
        await attachFlashcardMedia(card.id, "answer", postData.fields.key, newAFile.name);
      } else if (aMediaAction === "add" && newAFile) {
        const postData = await getPresignedUploadData(card.id, newAFile.name, false);
        await uploadFileToS3(postData, newAFile);
        await attachFlashcardMedia(card.id, "answer", postData.fields.key, newAFile.name);
      }


      onSaved();
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError("Something went wrong. Please try again.");
      }
    } finally {
      setSaving(false);
    }
  }

  function renderMediaSection(
    side: "question" | "answer",
    meta: FlashcardData["questionMediaMetadata"],
    action: MediaAction,
    newFile: File | null,
    previewUrl: string | null,
  ) {
    const hasExisting = meta != null && meta.presignedDownloadUrl != null;

    return (
      <div className="media-manage">
        {/* Current media */}
        {hasExisting && action !== "remove" && action !== "replace" && (
          <div className="media-manage-current">
            <MediaRenderer url={meta!.presignedDownloadUrl} fileName={meta!.name} />
            <span className="media-manage-filename">{meta!.name}</span>
          </div>
        )}

        {/* Removed state */}
        {action === "remove" && (
          <div className="media-manage-removed">Media will be removed</div>
        )}

        {/* Pending replacement/addition */}
        {(action === "replace" || action === "add") && newFile && (
          <>
            {previewUrl && (
              <div className="media-manage-current">
                <MediaRenderer url={previewUrl} fileName={newFile.name} />
                <span className="media-manage-filename">{newFile.name}</span>
              </div>
            )}
            <div className="media-manage-pending">
              <span className="media-manage-pending-name">
                {action === "replace" ? "Replace with: " : "Add: "}
                {newFile.name}
              </span>
            </div>
          </>
        )}

        {/* Action buttons */}
        <div className="media-manage-actions" style={{ marginTop: 8 }}>
          {action === "keep" && hasExisting && (
            <>
              <button
                type="button"
                className="btn btn-ghost btn-sm edit-card-delete-btn"
                onClick={() => handleRemove(side)}
                disabled={saving}
              >
                Remove
              </button>
              <label className="media-file-label">
                Replace
                <input
                  type="file"
                  accept=".jpg,.jpeg,.png,.gif,.webp,.mp3,.wav,.ogg"
                  className="media-file-input"
                  onChange={(e) => handleFileSelect(e.target.files?.[0] ?? null, side)}
                  disabled={saving}
                />
              </label>
            </>
          )}

          {action === "keep" && !hasExisting && (
            <label className="media-file-label">
              Add media
              <input
                type="file"
                accept=".jpg,.jpeg,.png,.gif,.webp,.mp3,.wav,.ogg"
                className="media-file-input"
                onChange={(e) => handleFileSelect(e.target.files?.[0] ?? null, side)}
                disabled={saving}
              />
            </label>
          )}

          {action !== "keep" && (
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => handleUndo(side)}
              disabled={saving}
            >
              Undo
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card edit-card-modal" onClick={(e) => e.stopPropagation()}>
        <h2 className="modal-heading">Edit Card</h2>
        <form onSubmit={handleSave}>
          {/* Question Section */}
          <div className="form-field">
            <div className="edit-card-section-label">Question</div>
            <input
              ref={questionRef}
              className="form-input"
              type="text"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              disabled={saving}
            />
            {renderMediaSection("question", card.questionMediaMetadata, qMediaAction, newQFile, newQPreviewUrl)}
          </div>

          {/* Answer Section */}
          <div className="form-field">
            <div className="edit-card-section-label">Answer</div>
            <input
              className="form-input"
              type="text"
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
              disabled={saving}
            />
            {renderMediaSection("answer", card.answerMediaMetadata, aMediaAction, newAFile, newAPreviewUrl)}
          </div>

          {error && <div className="form-error">{error}</div>}

          <div className="modal-actions">
            <button
              type="button"
              className="btn btn-ghost"
              onClick={onClose}
              disabled={saving}
            >
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? "Saving..." : "Save"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
