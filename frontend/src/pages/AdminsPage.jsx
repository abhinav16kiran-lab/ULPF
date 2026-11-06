import { useCallback, useEffect, useRef, useState } from "react";
import Navbar from "../components/Navbar";
import {
  listAdmins, listPendingLinks, createInvite, createResetLink, revokeAdmin, cancelLink, linkUrl, errorMessage,
} from "../api/admins";
import "./AdminDashboardPage.css";

const formatDate = (value) => (value ? new Date(value).toLocaleDateString() : "");

function expiresIn(expiresAt) {
  const hours = Math.floor((new Date(expiresAt).getTime() - Date.now()) / 3_600_000);
  return hours < 1 ? "Expires in under an hour" : `Expires in ${hours} h`;
}

/** Copies the text; falls back to selecting the field where the Clipboard API is missing (plain http). */
async function copyText(text, input) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    input?.select();
    return document.execCommand?.("copy") ?? false;
  }
}

/** A link shown once, right after it is created: the token is never shown again. */
function NewLinkBox({ created, onDone }) {
  const inputRef = useRef(null);
  const [copied, setCopied] = useState(false);
  const url = linkUrl(created.token);

  return (
    <div className="admins-link-box" role="status">
      <p className="admins-link-box-title">{created.title}</p>
      <div className="admins-inline-form">
        <input ref={inputRef} className="admins-input" value={url} readOnly aria-label="Link" onFocus={(e) => e.target.select()} />
        <button type="button" className="admins-btn-primary"
          onClick={async () => setCopied(await copyText(url, inputRef.current))}>
          {copied ? "Copied" : "Copy"}
        </button>
        <button type="button" className="admins-btn-secondary" onClick={onDone}>Done</button>
      </div>
      <p className="admins-hint">Share it privately: anyone with this link can use it. It works once, for 12 hours, and won't be shown again.</p>
    </div>
  );
}

/**
 * The super-admin's Admins screen (/admin/admins): invite admins by link, reset a regular admin's password by link,
 * revoke admins, and cancel pending links. Destructive actions confirm inline; nothing uses alert() dialogs.
 */
function AdminsPage() {
  const [admins, setAdmins] = useState([]);
  const [links, setLinks] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState(null);
  const [inviting, setInviting] = useState(false);
  const [label, setLabel] = useState("");
  const [created, setCreated] = useState(null);
  const [confirmRevoke, setConfirmRevoke] = useState(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [adminList, linkList] = await Promise.all([listAdmins(), listPendingLinks()]);
      setAdmins(adminList);
      setLinks(linkList);
    } catch (err) {
      setError(errorMessage(err, "Could not load admins."));
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function run(action) {
    setError(null);
    setBusy(true);
    try {
      await action();
      await refresh();
    } catch (err) {
      setError(errorMessage(err, "That didn't work. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  const invite = (e) => {
    e.preventDefault();
    run(async () => {
      const link = await createInvite(label.trim() || null);
      setCreated({ ...link, title: label.trim() ? `Invite link for ${label.trim()}` : "Invite link" });
      setInviting(false);
      setLabel("");
    });
  };

  const reset = (admin) => run(async () => {
    const link = await createResetLink(admin.userId);
    setCreated({ ...link, title: `Password reset link for @${admin.username}` });
  });

  const revoke = (admin) => run(async () => {
    await revokeAdmin(admin.userId);
    setConfirmRevoke(null);
  });

  return (
    <div className="admin-dashboard-container">
      <Navbar />
      <main className="admin-main-wrapper">
        <div className="admin-top-header">
          <div className="admin-header-title-group">
            <h1 className="admin-header-title">Admins</h1>
          </div>
          {!inviting && (
            <button type="button" className="admins-btn-primary" onClick={() => { setCreated(null); setInviting(true); }}>
              Invite admin
            </button>
          )}
        </div>

        {error && <div className="admins-error">{error}</div>}

        {inviting && (
          <form className="admins-inline-form" onSubmit={invite}>
            <input className="admins-input" value={label} onChange={(e) => setLabel(e.target.value)} maxLength={100}
              placeholder="Who is it for? (optional)" aria-label="Who is it for?" autoFocus />
            <button type="submit" className="admins-btn-primary" disabled={busy}>Create link</button>
            <button type="button" className="admins-btn-secondary" onClick={() => setInviting(false)}>Cancel</button>
          </form>
        )}

        {created && <NewLinkBox created={created} onDone={() => setCreated(null)} />}

        <section className="admin-inspector-card admins-section">
          <h2 className="admins-section-title">Admin accounts</h2>
          {loaded && (
            <ul className="admins-list">
              {admins.map((admin) => (
                <li key={admin.userId} className="admins-row">
                  <div>
                    <div className="admins-row-name">
                      <span>{admin.username}</span>
                      {admin.superAdmin && <span className="admin-badge-pill">Super-admin</span>}
                    </div>
                    <div className="admins-row-meta">Added {formatDate(admin.createdAt)}</div>
                  </div>
                  {!admin.superAdmin && (confirmRevoke === admin.userId ? (
                    <div className="admins-actions">
                      <span className="admins-hint">Revoke @{admin.username}? Their account is deleted and they're signed out.</span>
                      <button type="button" className="admins-btn-danger" disabled={busy} onClick={() => revoke(admin)}>Yes, revoke</button>
                      <button type="button" className="admins-btn-secondary" onClick={() => setConfirmRevoke(null)}>Cancel</button>
                    </div>
                  ) : (
                    <div className="admins-actions">
                      <button type="button" className="admins-btn-secondary" disabled={busy} onClick={() => reset(admin)}>Reset password</button>
                      <button type="button" className="admins-btn-danger" onClick={() => setConfirmRevoke(admin.userId)}>Revoke</button>
                    </div>
                  ))}
                </li>
              ))}
            </ul>
          )}
        </section>

        {links.length > 0 && (
          <section className="admin-inspector-card admins-section">
            <h2 className="admins-section-title">Pending links</h2>
            <ul className="admins-list">
              {links.map((link) => (
                <li key={link.linkId} className="admins-row">
                  <div>
                    <div className="admins-row-name">
                      {link.kind === "INVITE" ? `Invite${link.label ? ` · ${link.label}` : ""}` : `Password reset · @${link.targetUsername}`}
                    </div>
                    <div className="admins-row-meta">{expiresIn(link.expiresAt)}</div>
                  </div>
                  <button type="button" className="admins-btn-secondary" disabled={busy} onClick={() => run(() => cancelLink(link.linkId))}>
                    Cancel link
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}
      </main>
    </div>
  );
}

export default AdminsPage;
