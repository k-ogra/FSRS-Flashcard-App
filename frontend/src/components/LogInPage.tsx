import { useState } from "react";
import { Link } from "react-router-dom";
import { login, ApiError } from "../api";
import "./SignupLoginPage.css";

export default function LogInPage() {
  const [formState, setFormState] = useState({
    username: "",
    password: "",
  });
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
    setFormState((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const res = await login(formState.username, formState.password);
      console.log("Logged in as", res.username);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError("Something went wrong. Please try again.");
      }
    } finally {
      setLoading(false);
    }
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

            {error && <div className="form-error">{error}</div>}

            <button type="submit" className="btn btn-primary signup-submit" disabled={loading}>
              {loading ? "Logging in..." : "Log in"}
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
