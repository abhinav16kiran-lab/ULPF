import { useState } from "react";

/**
 * Shows a new API key once, in a dialog, with a Copy button. The key exists only in the parent's state:
 * onClose must drop it, so nothing keeps the raw key after the vendor closes the dialog.
 */
function ApiKeyReveal({ apiKey, sourceName, replaced, onClose }) {
  const [copied, setCopied] = useState(false);

  const copy = () => {
    if (navigator.clipboard) {
      navigator.clipboard.writeText(apiKey);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <div className="onboarding-modal-overlay">
      <div className="onboarding-modal-card" role="dialog" aria-modal="true" aria-labelledby="api-key-title">
        <h3 id="api-key-title" style={{ fontSize: "1.25rem", fontWeight: "bold", color: "#111827", marginBottom: "0.5rem" }}>
          {replaced ? "New API key" : "Your API key"}
        </h3>
        <p style={{ fontSize: "0.875rem", color: "#4b5563", marginBottom: "1rem", lineHeight: 1.6 }}>
          For log source <strong>{sourceName}</strong>.{" "}
          {replaced && "The previous key no longer works. "}
          Copy it now. You cannot see it again.
        </p>
        <div style={{ backgroundColor: "#fffbeb", border: "1px solid rgba(253, 230, 138, 0.8)", borderRadius: "0.75rem", padding: "0.625rem", display: "flex", alignItems: "center", justifyContent: "space-between", gap: "0.5rem", marginBottom: "1.25rem", textAlign: "left" }}>
          <span style={{ fontFamily: "var(--font-mono)", fontSize: "0.75rem", fontWeight: 600, color: "#1f2937", wordBreak: "break-all", userSelect: "all" }}>
            {apiKey}
          </span>
          <button onClick={copy} type="button" style={{ flexShrink: 0, fontSize: "0.6875rem", fontWeight: 600, color: "var(--ulpf-teal-dark)", border: "1px solid #ccfbf1", backgroundColor: "rgba(240, 253, 250, 0.7)", padding: "0.25rem 0.625rem", borderRadius: "0.5rem", cursor: "pointer" }}>
            {copied ? "Copied" : "Copy"}
          </button>
        </div>
        <button onClick={onClose} type="button" className="onboarding-submit-btn" style={{ fontSize: "0.875rem", padding: "0.75rem 1.5rem", borderRadius: "9999px" }}>
          I have saved the key
        </button>
      </div>
    </div>
  );
}

export default ApiKeyReveal;
