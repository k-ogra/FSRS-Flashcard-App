import { useState, useRef, useEffect } from "react";
import { Link, useNavigate, useLocation } from "react-router-dom";
import { useAuth } from "../context/useAuth";
import { logout as apiLogout, deleteAccount as apiDeleteAccount } from "../../api";
import "./Navbar.css";

function PersonIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="12" cy="8" r="4" />
      <path d="M20 21a8 8 0 1 0-16 0" />
    </svg>
  );
}

function Navbar() {
  const navigate = useNavigate();
  const location = useLocation();
  const auth = useAuth();
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Close dropdown on outside click
  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
    }
    if (dropdownOpen) {
      document.addEventListener("mousedown", handleClick);
    }
    return () => document.removeEventListener("mousedown", handleClick);
  }, [dropdownOpen]);

  const handleFeaturesClick = (e: React.MouseEvent) => {
    e.preventDefault();
    if (location.pathname === "/") {
      document
        .getElementById("features")
        ?.scrollIntoView({ behavior: "smooth" });
    } else {
      navigate("/#features");
    }
  };

  async function handleLogout() {
    setDropdownOpen(false);
    try {
      await apiLogout();
    } catch {
      // Clear local state even if API call fails
    }
    auth.logoutSuccess();
    navigate("/");
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
      // Keep modal open on failure so user can retry
      setDeleting(false);
    }
  }

  return (
    <>
      <nav className="navbar">
        <div className="navbar-inner">
          <Link to="/" className="logo logo-btn" aria-label="Go to homepage">
            <span className="logo-mark">&#9733;</span>
            <span className="logo-text">FSRS Flashcard App</span>
          </Link>
          <ul className="nav-links">
            <li>
              <a href="#features" onClick={handleFeaturesClick}>
                Features
              </a>
            </li>
            {auth.isAuthenticated && (
              <>
                <li>
                  <Link to="/my-decks">My Decks</Link>
                </li>
                <li>
                  <Link to="/shared-decks">Shared Decks</Link>
                </li>
                <li>
                  <Link to="/public-decks">Public Decks</Link>
                </li>
              </>
            )}
          </ul>
          <div className="navbar-actions">
            {auth.loading ? null : auth.isAuthenticated ? (
              <div className="avatar-menu" ref={dropdownRef}>
                <span className="avatar-username">Logged in as {auth.username}</span>
                <button
                  className="avatar-btn"
                  onClick={() => setDropdownOpen((prev) => !prev)}
                  aria-label="Account menu"
                  aria-expanded={dropdownOpen}
                >
                  <PersonIcon />
                </button>
                {dropdownOpen && (
                  <div className="avatar-dropdown">
                    <button
                      className="avatar-dropdown-item"
                      onClick={handleLogout}
                    >
                      Log Out
                    </button>
                    <button
                      className="avatar-dropdown-item avatar-dropdown-item--danger"
                      onClick={() => {
                        setDropdownOpen(false);
                        setConfirmOpen(true);
                      }}
                    >
                      Delete Account
                    </button>
                  </div>
                )}
              </div>
            ) : (
              <>
                <Link to="/login" className="btn btn-outline btn-sm">
                  Log In
                </Link>
                <Link to="/signup" className="btn btn-primary btn-sm">
                  Get Started
                </Link>
              </>
            )}
          </div>
        </div>
      </nav>

      {/* Delete Account Confirmation Modal */}
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

export default Navbar;
