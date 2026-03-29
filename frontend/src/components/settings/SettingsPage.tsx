import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../context/useAuth";
import {
  deleteAccount as apiDeleteAccount,
  getUserSettings,
  updateUserSettings,
  ApiError,
} from "../../api";
import "./SettingsPage.css";

function SettingsPage() {
  const navigate = useNavigate();
  const auth = useAuth();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);

  // Settings state
  const [reviewAheadMinutes, setReviewAheadMinutes] = useState(20);
  const [savedMinutes, setSavedMinutes] = useState(20);
  const [loadingSettings, setLoadingSettings] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saveMsg, setSaveMsg] = useState<string | null>(null);

  useEffect(() => {
    if (!auth.isAuthenticated && !auth.loading) {
      navigate("/login");
      return;
    }
    if (!auth.isAuthenticated) return;
    getUserSettings()
      .then((s) => {
        setReviewAheadMinutes(s.reviewAheadMinutes);
        setSavedMinutes(s.reviewAheadMinutes);
      })
      .catch((err) => {
        if (err instanceof ApiError && err.status === 401) {
          auth.logoutSuccess();
          navigate("/login");
        }
      })
      .finally(() => setLoadingSettings(false));
  }, [auth.isAuthenticated, auth.loading]);

  async function handleSaveSettings() {
    setSaving(true);
    setSaveMsg(null);
    try {
      const updated = await updateUserSettings({ reviewAheadMinutes });
      setSavedMinutes(updated.reviewAheadMinutes);
      setReviewAheadMinutes(updated.reviewAheadMinutes);
      setSaveMsg("Settings saved.");
      setTimeout(() => setSaveMsg(null), 2000);
    } catch {
      setSaveMsg("Failed to save settings.");
    } finally {
      setSaving(false);
    }
  }

  async function handleDeleteAccount() {
    setDeleting(true);
    try {
      await apiDeleteAccount();
      setConfirmOpen(false);
      setDeleting(false);
      auth.logoutSuccess();
      navigate("/");
    } catch {
      setDeleting(false);
    }
  }

  if (auth.loading || loadingSettings) return null;

  const hasChanges = reviewAheadMinutes !== savedMinutes;

  return (
    <>
      <div className="settings-page">
        <div className="settings-inner">
          <h1 className="settings-heading">Settings</h1>
          <p className="settings-sub">Manage your account preferences.</p>

          <div className="settings-section" style={{ marginBottom: 24 }}>
            <h2 className="settings-section-title">Study</h2>
            <p className="settings-section-desc">
              Configure how your study sessions behave.
            </p>
            <div className="settings-field">
              <label className="settings-label" htmlFor="review-ahead">
                Review ahead time (minutes)
              </label>
              <p className="settings-field-help">
                Cards due within this many minutes from now will be included in
                your study session.
              </p>
              <input
                id="review-ahead"
                className="settings-input"
                type="number"
                min={0}
                max={1440}
                value={reviewAheadMinutes}
                onChange={(e) =>
                  setReviewAheadMinutes(
                    Math.max(0, Math.min(1440, Number(e.target.value) || 0)),
                  )
                }
              />
            </div>
            <div className="settings-save-row">
              <button
                className="btn btn-primary btn-sm"
                onClick={handleSaveSettings}
                disabled={saving || !hasChanges}
              >
                {saving ? "Saving..." : "Save"}
              </button>
              {saveMsg && <span className="settings-save-msg">{saveMsg}</span>}
            </div>
          </div>

          <div className="settings-section settings-section--danger">
            <h2 className="settings-section-title">Danger Zone</h2>
            <p className="settings-section-desc">
              Permanently delete your account, all your decks, and all shared
              decks. This action cannot be undone.
            </p>
            <button
              className="settings-delete-btn"
              onClick={() => setConfirmOpen(true)}
            >
              Delete Account
            </button>
          </div>
        </div>
      </div>

      {confirmOpen && (
        <div className="confirm-overlay" onClick={() => !deleting && setConfirmOpen(false)}>
          <div className="confirm-modal" onClick={(e) => e.stopPropagation()}>
            <h2 className="confirm-title">Delete your account?</h2>
            <p className="confirm-body">
              This will permanently delete your account, all your decks, and all
              shared decks. This action cannot be undone.
            </p>
            <div className="confirm-actions">
              <button
                className="btn btn-outline btn-sm"
                onClick={() => setConfirmOpen(false)}
                disabled={deleting}
              >
                Cancel
              </button>
              <button
                className="btn btn-sm confirm-delete-btn"
                onClick={handleDeleteAccount}
                disabled={deleting}
              >
                {deleting ? "Deleting..." : "Delete Account"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

export default SettingsPage;
