import "./Features.css";

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

export default Features;
