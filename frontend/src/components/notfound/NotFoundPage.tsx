import { Link } from "react-router-dom";

function NotFoundPage() {
  return (
    <section className="hero">
      <div className="hero-inner">
        <h1 className="hero-heading" style={{ fontSize: "clamp(64px, 10vw, 120px)" }}>
          <span className="accent">404</span>
        </h1>
        <p className="hero-sub">
          The page you're looking for doesn't exist or has been moved.
        </p>
        <div className="hero-actions">
          <Link to="/" className="btn btn-primary">Back to Home</Link>
        </div>
      </div>
    </section>
  );
}

export default NotFoundPage;
