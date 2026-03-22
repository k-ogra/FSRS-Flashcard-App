import { Link, useNavigate, useLocation } from "react-router-dom";
import { useAuth } from "../context/useAuth";
import { logout as apiLogout } from "../../api";

function Navbar() {
  const navigate = useNavigate();
  const location = useLocation();
  const auth = useAuth();

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
    try {
      await apiLogout();
    } catch {
      // Clear local state even if API call fails
    }
    auth.logoutSuccess();
    navigate("/");
  }

  return (
    <nav className="navbar">
      <div className="navbar-inner">
        <Link to="/" className="logo logo-btn" aria-label="Go to homepage">
          <span className="logo-mark">★</span>
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
            <button className="btn btn-outline btn-sm" onClick={handleLogout}>
              Logout
            </button>
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
  );
}

export default Navbar;
