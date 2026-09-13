import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import client from "../api/client";
import UlpfLogo from "../components/UlpfLogo";
import "./AuthLayout.css";

function SignupPage() {
  const navigate = useNavigate();

  // Form State
  const [name, setName] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [agreedToTerms, setAgreedToTerms] = useState(true);
  
  // UI State
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e) {
    e.preventDefault();
    setError(null);

    // Name validation
    const trimmedName = name.trim();
    if (trimmedName.length < 2 || trimmedName.length > 100) {
      setError("Name must be between 2 and 100 characters");
      return;
    }

    // Username validation
    const trimmedUsername = username.trim();
    if (trimmedUsername.length < 3 || trimmedUsername.length > 30) {
      setError("Username must be between 3 and 30 characters");
      return;
    }

    const usernameRegex = /^[a-zA-Z0-9_.-]+$/;
    if (!usernameRegex.test(trimmedUsername)) {
      setError("Username can only contain letters, numbers, underscores, hyphens, and periods");
      return;
    }

    // Password validation
    if (password.length < 6 || password.length > 100) {
      setError("Password must be between 6 and 100 characters");
      return;
    }

    // Password matching validation
    if (password !== confirmPassword) {
      setError("Passwords do not match");
      return;
    }

    if (!agreedToTerms) {
      setError("Please agree to the Terms of Service and Privacy Policy");
      return;
    }

    setLoading(true);

    try {
      await client.post("/v1/signup", {
        name: trimmedName,
        username: trimmedUsername,
        password,
        confirmPassword,
      });

      // Success — redirect to login
      navigate("/login");
    } catch (err) {
      if (err.response && err.response.data) {
        const data = err.response.data;
        if (typeof data === "string") {
          setError(data.startsWith("<") ? "Signup failed. Please try again later." : data);
        } else if (data.message) {
          setError(data.message);
        } else if (data.error) {
          setError(data.error);
        } else {
          setError("An unexpected error occurred during signup");
        }
      } else {
        setError(err.message || "An unexpected error occurred during signup");
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="auth-page-wrapper">
      <div className="auth-split-container">
        
        {/* LEFT COLUMN: AUTH FORM CARD */}
        <div className="auth-card">
          
          {/* Header & Logo */}
          <div className="auth-brand-header">
            <UlpfLogo className="h-9 w-auto" />
            <span className="version-badge">• V0.2 PROTOTYPE</span>
          </div>

          {/* Title & Description */}
          <div className="auth-header-text">
            <h2>Create Account</h2>
            <p>Sign up to start ingesting, parsing, and monitoring your logs in real-time.</p>
          </div>

          {/* Development Notice */}
          <div style={{
            background: "#fff3cd",
            border: "1px solid #ffe69c",
            color: "#856404",
            padding: "10px 14px",
            borderRadius: "8px",
            fontSize: "13px",
            fontWeight: 500,
            marginBottom: "20px",
            display: "flex",
            alignItems: "center",
            gap: "8px"
          }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
              <line x1="12" y1="9" x2="12" y2="13"/>
              <line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>
            Note: Platform is currently in active development phase.
          </div>

          {/* Error Notice if any */}
          {error && (
            <div style={{
              background: "#ffe4e6",
              border: "1px solid #fecdd3",
              color: "#be123c",
              padding: "10px 14px",
              borderRadius: "10px",
              fontSize: "13px",
              fontWeight: 500
            }}>
              {error}
            </div>
          )}

          {/* Form Fields */}
          <form className="auth-form" onSubmit={handleSubmit}>
            
            {/* Full Name */}
            <div className="form-group">
              <label className="form-label" htmlFor="signup-name">Full Name</label>
              <div className="input-with-icon">
                <div className="input-icon-prefix">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="3" y="4" width="18" height="16" rx="2" />
                    <circle cx="9" cy="10" r="2" />
                    <line x1="15" y1="8" x2="17" y2="8" />
                    <line x1="15" y1="12" x2="17" y2="12" />
                    <line x1="7" y1="16" x2="17" y2="16" />
                  </svg>
                </div>
                <input
                  id="signup-name"
                  type="text"
                  className="form-input"
                  placeholder="Alex Mercer"
                  maxLength={100}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
              </div>
            </div>

            {/* Username */}
            <div className="form-group">
              <div className="form-label-row">
                <label className="form-label" htmlFor="signup-username">Username</label>
                <span className="badge-subtle">Must be unique</span>
              </div>
              <div className="input-with-icon">
                <div className="input-icon-prefix">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="12" cy="12" r="4" />
                    <path d="M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8" />
                  </svg>
                </div>
                <input
                  id="signup-username"
                  type="text"
                  className="form-input"
                  placeholder="alexmercer"
                  maxLength={30}
                  minLength={3}
                  pattern="[a-zA-Z0-9_.-]+"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  required
                />
              </div>
            </div>

            {/* Password & Confirm Password Grid */}
            <div className="password-grid">
              
              {/* Password */}
              <div className="form-group">
                <label className="form-label" htmlFor="signup-password">Password</label>
                <div className="input-with-icon">
                  <input
                    id="signup-password"
                    type={showPassword ? "text" : "password"}
                    className="form-input no-prefix"
                    placeholder="••••••••"
                    maxLength={100}
                    minLength={6}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                  />
                  <button
                    type="button"
                    className="password-toggle-btn"
                    onClick={() => setShowPassword(!showPassword)}
                    tabIndex="-1"
                  >
                    {showPassword ? (
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                        <line x1="1" y1="1" x2="23" y2="23" />
                      </svg>
                    ) : (
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                        <circle cx="12" cy="12" r="3" />
                      </svg>
                    )}
                  </button>
                </div>
              </div>

              {/* Confirm Password */}
              <div className="form-group">
                <label className="form-label" htmlFor="signup-confirm-password">Confirm Password</label>
                <div className="input-with-icon">
                  <input
                    id="signup-confirm-password"
                    type={showConfirmPassword ? "text" : "password"}
                    className="form-input no-prefix"
                    placeholder="••••••••"
                    maxLength={100}
                    minLength={6}
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    required
                  />
                  <button
                    type="button"
                    className="password-toggle-btn"
                    onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                    tabIndex="-1"
                  >
                    {showConfirmPassword ? (
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                        <line x1="1" y1="1" x2="23" y2="23" />
                      </svg>
                    ) : (
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                        <circle cx="12" cy="12" r="3" />
                      </svg>
                    )}
                  </button>
                </div>
              </div>

            </div>

            {/* Checkbox Row */}
            <label className="checkbox-row">
              <input
                type="checkbox"
                checked={agreedToTerms}
                onChange={(e) => setAgreedToTerms(e.target.checked)}
              />
              <div className="checkbox-custom">
                {agreedToTerms && (
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                )}
              </div>
              <span>
                I agree to the <a href="#terms" className="terms-link" onClick={(e) => e.preventDefault()}>Terms of Service</a> and <a href="#privacy" className="terms-link" onClick={(e) => e.preventDefault()}>Privacy Policy</a>
              </span>
            </label>

            {/* Submit Action Button */}
            <button id="signup-submit-btn" type="submit" className="btn-primary-action" disabled={loading}>
              {loading ? (
                "Creating Account…"
              ) : (
                <>
                  Create Free Account
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <line x1="5" y1="12" x2="19" y2="12" />
                    <polyline points="12 5 19 12 12 19" />
                  </svg>
                </>
              )}
            </button>

          </form>

          {/* Sub-footer Micro Copy */}
          <div className="auth-sub-footer">
            <div className="assurance-tag">
              <svg className="assurance-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
              Takes under 30 seconds · Enterprise ready
            </div>

            <div className="auth-redirect-text">
              Already have an account?{" "}
              <Link to="/login" className="auth-redirect-link">
                Log in
              </Link>
            </div>
          </div>

        </div>

        {/* RIGHT COLUMN: CONTINUOUS WATERFALL LOG STREAM SHOWCASE */}
        <div className="auth-visual-panel">
          
          {/* Top Pipeline Header */}
          <div className="pipeline-header-bar">
            <div className="pipeline-title">
              <span className="pipeline-dot-active"></span>
              ULPF Real-Time Ingestion
            </div>
            <div className="pipeline-status-badge">
              LIVE PIPELINE
            </div>
          </div>

          {/* Continuous Falling Log Stream Canvas */}
          <div className="log-stream-canvas">
            <div className="pipeline-guide-lines"></div>

            <div className="falling-log-pill pill-green">
              • GET /api/v1/health 200
            </div>

            <div className="falling-log-pill pill-cyan">
              ⚡ CACHE_HIT edge-eu
            </div>

            <div className="falling-log-pill pill-amber">
              TOPIC::orders.placed
            </div>

            <div className="falling-log-pill pill-rose">
              ! AUTH_FAIL 401 · <span style={{ opacity: 0.7 }}>src: 192.168.4.12</span>
            </div>

            <div className="falling-log-pill pill-slate">
              SQL_INDEX_HIT (0.4ms)
            </div>

            {/* Mascot Widget Ingestion Point */}
            <div className="mascot-widget-card">
              <div className="mascot-face">
                <span className="mascot-eye"></span>
                <span className="mascot-cheeks"></span>
                <span className="mascot-eye"></span>
              </div>
              <div className="mascot-status-row">
                <span className="mascot-label">ULPF.INGEST</span>
                <span className="mascot-buffer">• Lossless Buffer</span>
              </div>
            </div>
          </div>

          {/* Bottom Showcase Text */}
          <div className="showcase-text-group">
            <div className="showcase-heading">
              Catching & organizing all your logs in real-time
              <span className="star-accent">✪</span>
            </div>
            <p className="showcase-description">
              Drop any unstructured payload, syslog, or trace. ULPF categorizes every single event with joyful zero-friction clarity.
            </p>
          </div>

        </div>

      </div>
    </div>
  );
}

export default SignupPage;
