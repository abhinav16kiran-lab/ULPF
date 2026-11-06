import { useEffect, useState } from "react";
import { useNavigate, useLocation, Link } from "react-router-dom";
import client from "../api/client";
import { getSetupStatus } from "../api/admins";
import UlpfLogo from "../components/UlpfLogo";
import AuthShowcase from "../components/AuthShowcase";
import "./AuthLayout.css";

function LoginPage() {
  const navigate = useNavigate();
  const notice = useLocation().state?.notice;

  // Form & Role State
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState("ADMIN");
  const [rememberMe, setRememberMe] = useState(true);

  // UI State
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const ROLES = ["ADMIN", "VENDOR", "USER"];

  // A fresh install has no super-admin yet: send the operator to the one-time setup screen.
  useEffect(() => {
    let active = true;
    getSetupStatus()
      .then((status) => {
        if (active && status.setupRequired) navigate("/setup", { replace: true });
      })
      .catch(() => {}); // the login form still works; a real outage shows when signing in
    return () => {
      active = false;
    };
  }, [navigate]);

  async function handleSubmit(e) {
    e.preventDefault();
    setError(null);

    // Client-side username validation
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

    // Client-side password validation
    if (password.length < 6 || password.length > 100) {
      setError("Password must be between 6 and 100 characters");
      return;
    }

    setLoading(true);

    try {
      const response = await client.post("/v1/login", {
        username: trimmedUsername,
        password,
        role,
      });

      // Store auth data in localStorage
      localStorage.setItem("token", response.data.token);
      localStorage.setItem("username", response.data.username);
      localStorage.setItem("role", response.data.role);
      localStorage.setItem("superAdmin", String(response.data.superAdmin === true));

      // Navigate based on assigned role
      if (response.data.role === "ADMIN") {
        navigate("/admin");
      } else {
        navigate("/onboard");
      }
    } catch (err) {
      if (err.response && err.response.data) {
        const data = err.response.data;
        if (typeof data === "string") {
          setError(data.startsWith("<") ? "Login failed. Please try again later." : data);
        } else if (data.message) {
          setError(data.message);
        } else if (data.error) {
          setError(data.error);
        } else {
          setError("An unexpected error occurred during login");
        }
      } else {
        setError(err.message || "An unexpected error occurred during login");
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
            <h2>Welcome Back</h2>
            <p>Sign in to access your logs, pipelines, and telemetry in real-time.</p>
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

          {notice && <div className="auth-inline-notice">{notice}</div>}

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
            
            {/* Account Role Selector Tabs */}
            <div className="form-group">
              <div className="form-label-row">
                <label className="form-label">Account Role</label>
                <span className="badge-subtle">Standard Access</span>
              </div>
              <div className="role-tabs-container">
                {ROLES.map((r) => (
                  <button
                    key={r}
                    type="button"
                    className={`role-tab-btn ${role === r ? "active" : ""}`}
                    onClick={() => setRole(r)}
                  >
                    {r}
                  </button>
                ))}
              </div>
            </div>

            {/* Hidden native select for accessibility/testing compatibility */}
            <select
              id="login-role"
              value={role}
              onChange={(e) => setRole(e.target.value)}
              style={{ display: "none" }}
            >
              {ROLES.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>

            {/* Username or Email */}
            <div className="form-group">
              <div className="form-label-row">
                <label className="form-label" htmlFor="login-username">Username or Email</label>
                <span className="badge-subtle">LDAP / SSO</span>
              </div>
              <div className="input-with-icon">
                <div className="input-icon-prefix">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="12" cy="12" r="4" />
                    <path d="M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8" />
                  </svg>
                </div>
                <input
                  id="login-username"
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

            {/* Password */}
            <div className="form-group">
              <label className="form-label" htmlFor="login-password">Password</label>
              <div className="input-with-icon">
                <input
                  id="login-password"
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

            {/* Remember Me & Forgot Password Row */}
            <div className="remember-forgot-row">
              <label className="checkbox-row" style={{ marginTop: 0 }}>
                <input
                  type="checkbox"
                  checked={rememberMe}
                  onChange={(e) => setRememberMe(e.target.checked)}
                />
                <div className="checkbox-custom">
                  {rememberMe && (
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round">
                      <polyline points="20 6 9 17 4 12" />
                    </svg>
                  )}
                </div>
                <span>Remember me</span>
              </label>

              <a href="#forgot" className="forgot-link" onClick={(e) => e.preventDefault()}>
                Forgot password?
              </a>
            </div>

            {/* Submit Action Button */}
            <button id="login-submit-btn" type="submit" className="btn-primary-action" disabled={loading}>
              {loading ? (
                "Signing in…"
              ) : (
                <>
                  Sign In
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
              Don't have an account?{" "}
              <Link to="/signup" className="auth-redirect-link">
                Sign up
              </Link>
            </div>
          </div>

        </div>

        <AuthShowcase />

      </div>
    </div>
  );
}

export default LoginPage;
