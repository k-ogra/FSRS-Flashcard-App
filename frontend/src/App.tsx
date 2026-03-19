import { useState } from "react";
import { Routes, Route, Link } from "react-router-dom";
import "./App.css";

function Navbar() {
  return (
    <nav className="navbar">
      <div className="navbar-inner">
        <Link to="/" className="logo logo-btn" aria-label="Go to homepage">
          <span className="logo-mark">★</span>
          <span className="logo-text">FSRS Flashcard App</span>
        </Link>
        <ul className="nav-links">
          <li><a href="#features">Features</a></li>
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

function SignUpPage() {
  const [formState, setFormState] = useState({
    username: "",
    password: "",
    confirmPassword: "",
  });
  const [submitted, setSubmitted] = useState(false);

  function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
    setFormState((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  }

  function handleSubmit(e: { preventDefault(): void }) {
    e.preventDefault();
    setSubmitted(true);
  }

  return (
    <div className="signup-page">
      <div className="signup-bg-glow" aria-hidden="true" />
      <div className="signup-container">
        <div className="signup-card">
          <div className="signup-card-header">
            <h1 className="signup-heading">Create your account</h1>
            <p className="signup-sub">
              Start learning smarter with FSRS-6.
            </p>
          </div>

          {submitted ? (
            <div className="signup-success">
              <div className="signup-success-icon">✓</div>
              <h2 className="signup-success-heading">You're on the list</h2>
              <p className="signup-success-sub">
                Welcome aboard, <strong>{formState.username}</strong>!
              </p>
            </div>
          ) : (
            <form className="signup-form" onSubmit={handleSubmit} noValidate>
              <div className="form-field">
                <label className="form-label" htmlFor="username">Username</label>
                <input
                  id="username"
                  name="username"
                  type="text"
                  className="form-input"
                  placeholder="Choose a username"
                  value={formState.username}
                  onChange={handleChange}
                  required
                  autoComplete="username"
                />
              </div>

              <div className="form-field">
                <label className="form-label" htmlFor="password">Password</label>
                <input
                  id="password"
                  name="password"
                  type="password"
                  className="form-input"
                  placeholder="At least 8 characters"
                  value={formState.password}
                  onChange={handleChange}
                  required
                  minLength={8}
                  autoComplete="new-password"
                />
              </div>

              <div className="form-field">
                <label className="form-label" htmlFor="confirmPassword">Confirm password</label>
                <input
                  id="confirmPassword"
                  name="confirmPassword"
                  type="password"
                  className="form-input"
                  placeholder="Repeat your password"
                  value={formState.confirmPassword}
                  onChange={handleChange}
                  required
                  autoComplete="new-password"
                />
              </div>

              <button type="submit" className="btn btn-primary signup-submit">
                Create account
              </button>
            </form>
          )}

          <div className="signup-divider">
            <span>Already have an account?</span>
          </div>
          <Link to="/login" className="btn btn-outline signup-login-btn">
            Log in instead
          </Link>
        </div>

        <Link to="/" className="signup-back">
          ← Back to home
        </Link>
      </div>
    </div>
  );
}

function LogInPage() {
  const [formState, setFormState] = useState({
    username: "",
    password: "",
  });

  function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
    setFormState((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  }

  function handleSubmit(e: { preventDefault(): void }) {
    e.preventDefault();
  }

  return (
    <div className="signup-page">
      <div className="signup-bg-glow" aria-hidden="true" />
      <div className="signup-container">
        <div className="signup-card">
          <div className="signup-card-header">
            <h1 className="signup-heading">Welcome back</h1>
            <p className="signup-sub">
              Log in to continue your learning journey.
            </p>
          </div>

          <form className="signup-form" onSubmit={handleSubmit} noValidate>
            <div className="form-field">
              <label className="form-label" htmlFor="login-username">Username</label>
              <input
                id="login-username"
                name="username"
                type="text"
                className="form-input"
                placeholder="Enter your username"
                value={formState.username}
                onChange={handleChange}
                required
                autoComplete="username"
              />
            </div>

            <div className="form-field">
              <label className="form-label" htmlFor="login-password">Password</label>
              <input
                id="login-password"
                name="password"
                type="password"
                className="form-input"
                placeholder="Enter your password"
                value={formState.password}
                onChange={handleChange}
                required
                autoComplete="current-password"
              />
            </div>

            <button type="submit" className="btn btn-primary signup-submit">
              Log in
            </button>
          </form>

          <div className="signup-divider">
            <span>Don't have an account?</span>
          </div>
          <Link to="/signup" className="btn btn-outline signup-login-btn">
            Sign up instead
          </Link>
        </div>

        <Link to="/" className="signup-back">
          ← Back to home
        </Link>
      </div>
    </div>
  );
}

function LandingPage() {
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
