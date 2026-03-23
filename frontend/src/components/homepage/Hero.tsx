import { Link } from "react-router-dom";
import { useAuth } from "../context/useAuth";
import "./Hero.css";

function Hero() {
  const { isAuthenticated } = useAuth();

  return (
    <section className="hero">
      <div className="hero-inner">
        <div className="hero-badge">Backed by FSRS-6</div>
        <h1 className="hero-heading">
          Study Smarter.
          <br />
          <span className="accent">Remember More.</span>
        </h1>
        <p className="hero-sub">
          The FSRS Flashcard App uses the Free Spaced Repetition Scheduler 6
          algorithm to show you cards at the exact moment you're about to forget
          them — maximizing retention with minimal effort.
        </p>
        <div className="hero-actions">
          {isAuthenticated ? (
            <Link to="/decks" className="btn btn-primary">
              Go to My Decks
            </Link>
          ) : (
            <Link to="/signup" className="btn btn-primary">
              Start for Free
            </Link>
          )}
          <a href="#how-it-works" className="btn btn-ghost">
            See how it works →
          </a>
        </div>
      </div>
    </section>
  );
}

export default Hero;
