import "./App.css";

function Navbar() {
  return (
    <nav className="navbar">
      <div className="navbar-inner">
        <div className="logo">
          <span className="logo-mark">★</span>
          <span className="logo-text">FSRS Flashcard App</span>
        </div>
        <ul className="nav-links">
          <li><a href="#features">Features</a></li>
        </ul>
        <div className="navbar-actions">
          <a href="#" className="btn btn-outline btn-sm">Log In</a>
          <a href="#" className="btn btn-primary btn-sm">Get Started</a>
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
          <a href="#" className="btn btn-primary">Start for Free</a>
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

function App() {
  return (
    <div className="app">
      <Navbar />
      <Hero />
      <Features />
      <Footer />
    </div>
  );
}

export default App;
