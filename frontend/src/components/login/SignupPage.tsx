import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { signup, ApiError } from "../../api";
import { useAuth } from "../context/useAuth";
import "./SignupLoginPage.css";

export default function SignupPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [formState, setFormState] = useState({
    username: "",
    password: "",
    confirmPassword: "",
  });
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
    setFormState((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  }

  async function handleSubmit(e: React.SubmitEvent) {
    e.preventDefault();
    setError(null);

    if (formState.password !== formState.confirmPassword) {
      setError("Passwords do not match.");
      return;
    }

    setLoading(true);
    try {
      await signup(formState.username, formState.password);
      auth.loginSuccess(formState.username);
      navigate("/my-decks");
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
            <h1 className="signup-heading">Create your account</h1>
            <p className="signup-sub">Start learning smarter with FSRS-6.</p>
          </div>

          <form className="signup-form" onSubmit={handleSubmit} noValidate>
            <div className="form-field">
              <label className="form-label" htmlFor="username">
                Username
              </label>
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
              <label className="form-label" htmlFor="password">
                Password
              </label>
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
              <label className="form-label" htmlFor="confirmPassword">
                Confirm password
              </label>
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

            {error && <div className="form-error">{error}</div>}

            <button
              type="submit"
              className="btn btn-primary signup-submit"
              disabled={loading}
            >
              {loading ? "Creating account..." : "Create account"}
            </button>
          </form>

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
