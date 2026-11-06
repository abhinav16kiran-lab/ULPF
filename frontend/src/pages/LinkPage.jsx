import { useEffect, useState } from "react";
import { useNavigate, useParams, Link } from "react-router-dom";
import { describeLink, submitLink, adminPasswordProblem, errorMessage, ADMIN_PASSWORD_MIN } from "../api/admins";
import AuthField from "../components/AuthField";
import UlpfLogo from "../components/UlpfLogo";
import AuthShowcase from "../components/AuthShowcase";
import "./AuthLayout.css";

/**
 * Opens a link from the super-admin (/link/:token). An invite link creates a regular admin account; a reset
 * link sets a new password for one admin. Used, expired, cancelled and unknown links all show the same message.
 */
function LinkPage() {
  const { token } = useParams();
  const navigate = useNavigate();
  const [link, setLink] = useState(null);
  const [invalid, setInvalid] = useState(false);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let active = true;
    describeLink(token)
      .then((info) => active && setLink(info))
      .catch(() => active && setInvalid(true));
    return () => {
      active = false;
    };
  }, [token]);

  const isInvite = link?.kind === "INVITE";

  async function handleSubmit(e) {
    e.preventDefault();
    const problem = adminPasswordProblem(password, confirmPassword);
    if (problem) {
      setError(problem);
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await submitLink(token, isInvite
        ? { username: username.trim(), password, confirmPassword }
        : { password, confirmPassword });
      const notice = isInvite ? "Account created. Sign in as an admin." : "Password changed. Sign in with your new password.";
      navigate("/login", { replace: true, state: { notice } });
    } catch (err) {
      if (err?.response?.status === 410) setInvalid(true);
      else setError(errorMessage(err, "That didn't work. Please try again."));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="auth-page-wrapper">
      <div className="auth-split-container">
        <div className="auth-card">
          <div className="auth-brand-header">
            <UlpfLogo className="h-9 w-auto" />
          </div>

          {invalid && (
            <>
              <div className="auth-header-text">
                <h2>This link doesn't work</h2>
                <p>This link is no longer valid. Links work once, for 12 hours, and the super-admin can cancel them. Ask the super-admin for a new one.</p>
              </div>
              <Link to="/login" className="auth-redirect-link">Go to sign in</Link>
            </>
          )}

          {!invalid && link && (
            <form className="auth-form" onSubmit={handleSubmit}>
              <div className="auth-header-text">
                <h2>{isInvite ? "Join as an admin" : "Set a new password"}</h2>
                <p>{isInvite ? "Choose the username and password you'll sign in with." : `For @${link.targetUsername}.`}</p>
              </div>
              {error && <div className="auth-inline-error">{error}</div>}
              {isInvite && (
                <AuthField id="link-username" label="Username" value={username} onChange={setUsername}
                  autoComplete="username" autoFocus maxLength={30} />
              )}
              <AuthField id="link-password" label="Password" type="password" value={password} onChange={setPassword}
                autoComplete="new-password" autoFocus={!isInvite} hint={`At least ${ADMIN_PASSWORD_MIN} characters.`} />
              <AuthField id="link-confirm-password" label="Confirm password" type="password" value={confirmPassword}
                onChange={setConfirmPassword} autoComplete="new-password" />
              <button type="submit" className="btn-primary-action" disabled={saving}>
                {saving ? "Saving…" : isInvite ? "Create account" : "Set password"}
              </button>
            </form>
          )}
        </div>
        <AuthShowcase />
      </div>
    </div>
  );
}

export default LinkPage;
