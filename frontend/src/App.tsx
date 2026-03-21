import { useEffect } from "react";
import { Routes, Route, Link, useNavigate, useLocation } from "react-router-dom";
import SignUpPage from "./components/SignUpPage";
import LogInPage from "./components/LogInPage";
import "./App.css";

function Navbar() {
  const navigate = useNavigate();
  const location = useLocation();

  const handleFeaturesClick = (e: React.MouseEvent) => {
    e.preventDefault();
    if (location.pathname === "/") {
      document.getElementById("features")?.scrollIntoView({ behavior: "smooth" });
    } else {
      navigate("/#features");
    }
  };

  return (
    <nav className="navbar">
      <div className="navbar-inner">
        <Link to="/" className="logo logo-btn" aria-label="Go to homepage">
          <span className="logo-mark">★</span>
          <span className="logo-text">FSRS Flashcard App</span>
        </Link>
        <ul className="nav-links">
          <li><a href="#features" onClick={handleFeaturesClick}>Features</a></li>
        </ul>
        <div className="navbar-actions">
          <Link to="/login" className="btn btn-outline btn-sm">Log In</Link>
          <Link to="/signup" className="btn btn-primary btn-sm">
            Get Started
          </Link>
        </div>
      </div>
    </nav>
  );
}

function Hero() {
  return (
    <section className="hero">
      <div className="hero-inner">
        <div className="hero-badge">Backed by FSRS-6</div>
        <h1 className="hero-heading">
          Study Smarter.<br />
          <span className="accent">Remember More.</span>
        </h1>
        <p className="hero-sub">
          The FSRS Flashcard App uses the Free Spaced Repetition
          Scheduler 6 algorithm to show you cards at the exact moment you're about to forget
          them — maximizing retention with minimal effort.
        </p>
        <div className="hero-actions">
          <Link to="/signup" className="btn btn-primary">
            Start for Free
          </Link>
          <a href="#how-it-works" className="btn btn-ghost">See how it works →</a>
        </div>
      </div>
    </section>
  );
}

interface FeatureCardProps {
  icon: string;
  title: string;
  description: string;
}

function FeatureCard({ icon, title, description }: FeatureCardProps) {
  return (
    <div className="feature-card">
      <div className="feature-icon">{icon}</div>
      <h3 className="feature-title">{title}</h3>
      <p className="feature-desc">{description}</p>
    </div>
  );
}

function Features() {
  const features = [
    {
      icon: "⟳",
      title: "FSRS-6 Algorithm",
      description:
        "Uses spaced repetition to predict when you'll forget a card and schedules reviews accordingly.",
    },
    {
      icon: "▶",
      title: "Media Integrated Cards",
      description:
        "Create media integrated cards with images, audio, or video.",
    },
    {
      icon: "▦",
      title: "Shareable Decks",
      description:
        "Browse and share decks created by other users. Or create your own decks and share them with the public.",
    }
  ];

  return (
    <section className="features" id="features">
      <div className="section-inner">
        <div className="section-header">
          <span className="section-label">Features</span>
          <h2 className="section-heading">Everything you need to master any subject</h2>
          <p className="section-sub">
            Built for serious learners — from medical students to language enthusiasts.
          </p>
        </div>
        <div className="features-grid">
          {features.map((f) => (
            <FeatureCard key={f.title} {...f} />
          ))}
        </div>
      </div>
    </section>
  );
}

function Footer() {
  return (
    <footer className="footer">
      <div className="footer-inner">
        <div className="footer-brand">
          <div className="logo">
            <span className="logo-mark">★</span>
            <span className="logo-text">FSRS Flashcard App</span>
          </div>
        </div>
        <div className="footer-links">
          <div className="footer-col">
            <div className="footer-col-title">Resources</div>
            <ul>
              <li><a href="#">GitHub</a></li>
            </ul>
          </div>
        </div>
      </div>
    </footer>
  );
}

function LandingPage() {
  const location = useLocation();

  useEffect(() => {
    if (location.hash) {
      const el = document.getElementById(location.hash.slice(1));
      if (el) {
        // Small delay to ensure the DOM is rendered
        setTimeout(() => el.scrollIntoView({ behavior: "smooth" }), 0);
      }
    }
  }, [location.hash]);

  return (
    <>
      <Hero />
      <Features />
      <Footer />
    </>
  );
}

function App() {
  return (
    <div className="app">
      <Navbar />
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route path="/signup" element={<SignUpPage />} />
        <Route path="/login" element={<LogInPage />} />
      </Routes>
    </div>
  );
}

export default App;
