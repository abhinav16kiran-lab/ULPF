/**
 * The animated right-hand panel of the auth screens: log lines falling into the ULPF ingest mascot.
 * Decorative only (no logic). Shared by login, sign-up, setup and the admin link page so they look the same;
 * AuthLayout.css styles it and hides it below 1024 px.
 */
function AuthShowcase() {
  return (
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
  );
}

export default AuthShowcase;
