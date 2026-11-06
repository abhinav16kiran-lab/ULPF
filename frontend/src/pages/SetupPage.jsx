import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getSetupStatus, completeSetup, adminPasswordProblem, errorMessage, ADMIN_PASSWORD_MIN } from "../api/admins";
import AuthField from "../components/AuthField";
import UlpfLogo from "../components/UlpfLogo";
import AuthShowcase from "../components/AuthShowcase";
import "./AuthLayout.css";

/**
 * One-time setup (/setup): step 1 proves the operator controls the server (the setup credentials from .env),
 * step 2 creates the super-admin. Shown only while no super-admin exists; afterwards it sends you to login.
 */
function SetupPage() {
  const navigate = useNavigate();
  const [ready, setReady] = useState(false);
  const [step, setStep] = useState(1);
  const [setupUsername, setSetupUsername] = useState("");
  const [setupPassword, setSetupPassword] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let active = true;
    getSetupStatus()
      .then((status) => {
        if (!active) return;
        if (status.setupRequired) setReady(true);
        else navigate("/login", { replace: true });
      })
      .catch(() => active && setReady(true)); // show the form; the server answers on submit
    return () => {
      active = false;
    };
  }, [navigate]);

  function continueToAccount(e) {
    e.preventDefault();
    setError(null);
    setStep(2);
  }

  async function createSuperAdmin(e) {
    e.preventDefault();
    const problem = adminPasswordProblem(password, confirmPassword);
    if (problem) {
      setError(problem);
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await completeSetup({ setupUsername, setupPassword, username: username.trim(), password, confirmPassword });
      navigate("/login", { replace: true, state: { notice: "Super-admin created. Sign in with your new account." } });
    } catch (err) {
      if (err?.response?.status === 401) {
        setStep(1);
        setError("Those setup credentials don't match the server's .env file.");
      } else {
        setError(errorMessage(err, "Setup failed. Please try again."));
      }
    } finally {
      setSaving(false);
    }
  }

  if (!ready) return <div className="auth-page-wrapper" />;

  return (
    <div className="auth-page-wrapper">
      <div className="auth-split-container">
        <div className="auth-card">
          <div className="auth-brand-header">
            <UlpfLogo className="h-9 w-auto" />
          </div>

          {step === 1 ? (
            <form className="auth-form" onSubmit={continueToAccount}>
              <div className="auth-header-text">
                <p className="auth-step-label">Step 1 of 2</p>
                <h2>Set up ULPF</h2>
                <p>Enter the setup credentials from the server's .env file. You need them only once.</p>
              </div>
              {error && <div className="auth-inline-error">{error}</div>}
              <AuthField id="setup-username" label="Setup username" value={setupUsername} onChange={setSetupUsername}
                autoComplete="off" autoFocus />
              <AuthField id="setup-password" label="Setup password" type="password" value={setupPassword}
                onChange={setSetupPassword} autoComplete="off" />
              <button type="submit" className="btn-primary-action">Continue</button>
            </form>
          ) : (
            <form className="auth-form" onSubmit={createSuperAdmin}>
              <div className="auth-header-text">
                <p className="auth-step-label">Step 2 of 2</p>
                <h2>Create your super-admin</h2>
                <p>You'll sign in with this account from now on. Only the super-admin can add or remove admins.</p>
              </div>
              {error && <div className="auth-inline-error">{error}</div>}
              <AuthField id="setup-new-username" label="Username" value={username} onChange={setUsername}
                autoComplete="username" autoFocus maxLength={30} />
              <AuthField id="setup-new-password" label="Password" type="password" value={password} onChange={setPassword}
                autoComplete="new-password" hint={`At least ${ADMIN_PASSWORD_MIN} characters.`} />
              <AuthField id="setup-confirm-password" label="Confirm password" type="password" value={confirmPassword}
                onChange={setConfirmPassword} autoComplete="new-password" />
              <button type="submit" className="btn-primary-action" disabled={saving}>
                {saving ? "Creating…" : "Create super-admin"}
              </button>
              <button type="button" className="auth-text-button" onClick={() => { setError(null); setStep(1); }}>
                Back
              </button>
            </form>
          )}
        </div>
        <AuthShowcase />
      </div>
    </div>
  );
}

export default SetupPage;
