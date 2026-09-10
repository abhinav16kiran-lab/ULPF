import { useState, useEffect, useCallback } from "react";
import { Link, useNavigate } from "react-router-dom";
import client from "../api/client";
import Navbar from "../components/Navbar";
import EmptyState from "../components/EmptyState";
import UlpfLogo from "../components/UlpfLogo";
import "./AdminDashboardPage.css";

function AdminDashboardPage() {
  const navigate = useNavigate();

  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [integrityBlocks, setIntegrityBlocks] = useState([]);
  const [adminStats, setAdminStats] = useState(null);

  // Active selected request
  const [selectedReqId, setSelectedReqId] = useState(null);

  // Layout & Inspector View states: "queue" | "inspector"
  const [activeTab, setActiveTab] = useState("inspector");
  const [inspectorTab, setInspectorTab] = useState("current"); // "current" | "diff"

  // Sandbox demo toggles
  const [sandboxSkeleton, setSandboxSkeleton] = useState(false);
  const [sandboxEmpty, setSandboxEmpty] = useState(false);

  // Candidate mapping editing state
  const [isEditingMapping, setIsEditingMapping] = useState(false);
  const [editingRows, setEditingRows] = useState([]);
  const [saveMappingLoading, setSaveMappingLoading] = useState(false);

  // Action decision states
  const [actionLoading, setActionLoading] = useState(null);
  const [showRejectModal, setShowRejectModal] = useState(false);
  const [rejectFeedback, setRejectFeedback] = useState("");

  // Toast message state
  const [toastMessage, setToastMessage] = useState(null);

  const showToast = (msg) => {
    setToastMessage(msg);
    setTimeout(() => {
      setToastMessage(null);
    }, 3000);
  };

  const fetchRequests = useCallback(async () => {
    setLoading(true);
    setError(null);

    try {
      const [reqRes, intRes] = await Promise.all([
        client.get("/v1/admin/onboard"),
        client.get("/v1/integrity/blocks").catch(() => ({ data: { blocks: [] } }))
      ]);

      const reqList = reqRes.data.requests || [];
      setRequests(reqList);
      if (reqRes.data.stats) {
        setAdminStats(reqRes.data.stats);
      }
      setIntegrityBlocks(intRes.data?.blocks || []);

      if (reqList.length > 0 && !selectedReqId) {
        setSelectedReqId(reqList[0].requestId);
      }
    } catch (err) {
      const errMsg = err.response?.data?.error || err.response?.data || err.message;
      setError(typeof errMsg === "string" ? errMsg : JSON.stringify(errMsg));
    } finally {
      setLoading(false);
    }
  }, [selectedReqId]);

  useEffect(() => {
    fetchRequests();
  }, [fetchRequests]);

  // Selected request record
  const currentReq = requests.find((r) => r.requestId === selectedReqId) || requests[0] || null;

  // Helper to parse sampleMetadata JSON
  const parseMetadata = (req) => {
    if (!req || !req.sampleMetadata) return {};
    try {
      return typeof req.sampleMetadata === "string"
        ? JSON.parse(req.sampleMetadata)
        : req.sampleMetadata;
    } catch {
      return {};
    }
  };

  const parsedMeta = parseMetadata(currentReq);
  const logType = parsedMeta.log_type || "REG_LOG";
  const isSensor = logType === "SEN_TEL" || logType === "SENSOR";

  // Helper to parse candidate mapping rows
  const parseCandidateRows = (req) => {
    const meta = parseMetadata(req);
    if (meta.candidate_mapping) {
      return Object.entries(meta.candidate_mapping).map(([raw, mapped], idx) => {
        const canonical = typeof mapped === "object" ? mapped.canonicalField || "unmapped" : String(mapped);
        let layerName = "ALIAS 1.0";
        let pctVal = 100;

        if (idx === 1) {
          layerName = "TYPO 0.95";
          pctVal = 95;
        } else if (idx === 2) {
          layerName = "TOKEN 0.90";
          pctVal = 90;
        } else if (idx >= 3) {
          layerName = "SEMANTIC 0.85";
          pctVal = 85;
        }

        return {
          raw,
          mapped: canonical,
          v1: "unmapped",
          layer: layerName,
          pct: pctVal,
          updated: true
        };
      });
    }
    return [
      { raw: "ev_type_id", mapped: "event.action", v1: "event.category", layer: "ALIAS 1.0", pct: 100, updated: true },
      { raw: "src_ip_addr", mapped: "source.ip", v1: "ip.src", layer: "TYPO 0.95", pct: 95, updated: true },
      { raw: "dst_prt", mapped: "destination.port", v1: "destination.port", layer: "TOKEN 0.90", pct: 90, updated: false },
      { raw: "usr_princ_nm", mapped: "user.email", v1: "user.name", layer: "SEMANTIC 0.85", pct: 85, updated: true }
    ];
  };

  const candidateRows = parseCandidateRows(currentReq);

  const startEditMode = () => {
    setIsEditingMapping(true);
    setEditingRows([...candidateRows]);
  };

  const cancelEditMode = () => {
    setIsEditingMapping(false);
  };

  const handleSaveMapping = async () => {
    if (!currentReq) return;
    setSaveMappingLoading(true);
    try {
      const mappingObj = {};
      editingRows.forEach((r) => {
        mappingObj[r.raw] = r.mapped;
      });

      await client.patch(`/v1/admin/onboard/${currentReq.requestId}/mapping`, {
        mappingJson: mappingObj,
      });

      showToast(`Saved candidate mappings for #${currentReq.requestId}`);
      setIsEditingMapping(false);
      await fetchRequests();
    } catch (err) {
      showToast("Error saving mapping: " + (err.response?.data?.error || err.message));
    } finally {
      setSaveMappingLoading(false);
    }
  };

  const handleDecision = async (decision, feedback = "") => {
    if (!currentReq) return;
    setActionLoading(currentReq.requestId);
    try {
      await client.put(`/v1/admin/onboard/${currentReq.requestId}`, {
        decision,
        feedbackNote: feedback
      });
      showToast(
        decision === "APPROVED"
          ? "Schema approved & live pipeline activated!"
          : "Request rejected. Feedback recorded."
      );
      setShowRejectModal(false);
      setRejectFeedback("");
      await fetchRequests();
    } catch (err) {
      showToast("Error: " + (err.response?.data?.error || err.message));
    } finally {
      setActionLoading(null);
    }
  };

  const pendingCount = adminStats?.pendingReviewCount !== undefined
    ? adminStats.pendingReviewCount
    : requests.filter((r) => r.status === "SUBMITTED").length;

  const approvedCount = adminStats?.activeVendorsCount !== undefined
    ? adminStats.activeVendorsCount
    : requests.filter((r) => r.status === "APPROVED").length;

  const copyToClipboard = (text) => {
    if (navigator.clipboard) {
      navigator.clipboard.writeText(text);
      showToast(`Copied ${text} to clipboard!`);
    }
  };

  return (
    <div className="admin-dashboard-container">
      <Navbar />

      <main className="admin-main-wrapper">
        {/* TOP BAR / NAVIGATION ACTIONS */}
        <div className="admin-top-header">
          <div className="admin-header-title-group">
            <h1 className="admin-header-title">Review Sandbox</h1>
            <span className="admin-badge-pill">
              <span className="admin-badge-dot"></span>
              Admin Console
            </span>
          </div>

          <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
            {/* SANDBOX CONTROLS */}
            <div style={{ display: "flex", alignItems: "center", gap: "8px", fontSize: "0.75rem", color: "#64748b" }}>
              <button
                type="button"
                onClick={() => setSandboxSkeleton(!sandboxSkeleton)}
                className="admin-pill-tag"
                style={{ cursor: "pointer", backgroundColor: sandboxSkeleton ? "#ccfbf1" : "#f1f5f9" }}
              >
                <span style={{ fontSize: "10px" }}>◯</span> Skeleton
              </button>
              <button
                type="button"
                onClick={() => setSandboxEmpty(!sandboxEmpty)}
                className="admin-pill-tag"
                style={{ cursor: "pointer", backgroundColor: sandboxEmpty ? "#ccfbf1" : "#f1f5f9" }}
              >
                <span style={{ fontSize: "10px" }}>❇</span> Empty State
              </button>
            </div>

            <Link
              to="/integrity"
              style={{
                backgroundColor: "#0d9488",
                color: "white",
                padding: "8px 16px",
                borderRadius: "9999px",
                textDecoration: "none",
                fontSize: "0.8rem",
                fontWeight: "700",
                display: "inline-flex",
                alignItems: "center",
                gap: "6px"
              }}
            >
              🔐 Tamper Audit ({integrityBlocks.length})
            </Link>
          </div>
        </div>

        {/* METRICS SUMMARY BAR */}
        <section className="admin-metrics-grid">
          <div className="admin-metric-card">
            <div className="admin-metric-icon-circle admin-metric-icon-cyan">
              ⏱
            </div>
            <div>
              <div className="admin-metric-value">{pendingCount}</div>
              <div className="admin-metric-label">Pending Review</div>
            </div>
          </div>

          <div className="admin-metric-card">
            <div className="admin-metric-icon-circle admin-metric-icon-blue">
              ✔
            </div>
            <div>
              <div className="admin-metric-value">{approvedCount}</div>
              <div className="admin-metric-label">Active Vendors</div>
            </div>
          </div>
        </section>

        {/* SEGMENTED TAB SWITCHER BAR */}
        <div className="admin-tab-switcher">
          <button
            onClick={() => setActiveTab("queue")}
            className={`admin-tab-btn ${activeTab === "queue" ? "active" : ""}`}
            type="button"
          >
            <span>📋</span> Request Queue ({requests.length})
          </button>
          <button
            onClick={() => setActiveTab("inspector")}
            className={`admin-tab-btn ${activeTab === "inspector" ? "active" : ""}`}
            type="button"
          >
            <span>👁</span> Live Inspector
          </button>
        </div>

        {/* SKELETON STATE SIMULATION */}
        {sandboxSkeleton && (
          <div style={{ display: "flex", flexDirection: "column", gap: "16px", opacity: 0.6 }}>
            <div style={{ height: "120px", backgroundColor: "#e2e8f0", borderRadius: "20px" }}></div>
            <div style={{ height: "300px", backgroundColor: "#e2e8f0", borderRadius: "20px" }}></div>
          </div>
        )}

        {/* EMPTY STATE SIMULATION */}
        {sandboxEmpty && (
          <div className="admin-inspector-card" style={{ textAlign: "center", padding: "60px 20px" }}>
            <EmptyState
              title="All caught up!"
              description="No pending requests in the review queue. All incoming vendor streams are mapped and active."
              transparent={true}
            />
          </div>
        )}

        {/* MAIN REVIEW WORKSPACE */}
        {!sandboxSkeleton && !sandboxEmpty && (
          loading ? (
            <div style={{ padding: "60px 0", textAlign: "center", color: "#64748b" }}>
              Loading review sandbox…
            </div>
          ) : error ? (
            <div style={{ padding: "60px 0", textAlign: "center", color: "#e11d48" }}>
              {error}
            </div>
          ) : activeTab === "queue" ? (
            /* REQUEST QUEUE LIST VIEW */
            <div style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
              {requests.map((req) => {
                const isSelected = req.requestId === selectedReqId;
                const meta = parseMetadata(req);
                const reqLogType = meta.log_type || "REG_LOG";

                return (
                  <div
                    key={req.requestId}
                    onClick={() => {
                      setSelectedReqId(req.requestId);
                      setActiveTab("inspector");
                    }}
                    style={{
                      backgroundColor: "white",
                      padding: "20px",
                      borderRadius: "18px",
                      boxShadow: "0 4px 16px -2px rgba(15, 23, 42, 0.04)",
                      cursor: "pointer",
                      borderLeft: isSelected ? "6px solid #0d9488" : "6px solid transparent",
                      display: "flex",
                      justifyContent: "space-between",
                      alignItems: "center"
                    }}
                  >
                    <div>
                      <div style={{ display: "flex", alignItems: "center", gap: "10px", marginBottom: "6px" }}>
                        <span className="admin-pill-tag">#{req.requestId.substring(0, 8)}</span>
                        <strong style={{ fontSize: "1rem", color: "#0f172a" }}>
                          {req.requestType}
                        </strong>
                        <span className="admin-badge-pill" style={{ fontSize: "0.6875rem", padding: "2px 8px" }}>
                          {reqLogType}
                        </span>
                      </div>
                      <p style={{ fontSize: "0.8rem", color: "#64748b", margin: 0 }}>
                        User: <code style={{ color: "#0f172a" }}>{req.userId}</code> | Source ID: <code style={{ color: "#0f172a" }}>{req.sourceId || "N/A"}</code> | Submitted: {req.createdAt ? String(req.createdAt).substring(0, 16) : "Recent"}
                      </p>
                    </div>

                    <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
                      <span className={`status-badge ${req.status === "APPROVED" ? "status-approved" : req.status === "REJECTED" ? "status-rejected" : "status-submitted"}`}>
                        {req.status}
                      </span>
                      <button type="button" className="admin-edit-mapping-btn" style={{ padding: "6px 14px" }}>
                        Inspect Candidate →
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            /* LIVE INSPECTOR VIEW CARD */
            currentReq ? (
              <div className="admin-inspector-card">
                {/* TOP TAGS & QUEUE BADGE ROW */}
                <div className="admin-inspector-header-tags">
                  <div className="admin-tag-list">
                    <span className="admin-pill-tag" onClick={() => copyToClipboard(currentReq.requestId)} style={{ cursor: "pointer" }} title="Click to copy request ID">
                      #{currentReq.requestId.substring(0, 10)} 📋
                    </span>
                    <span className="admin-pill-tag">
                      👤 @{currentReq.userId.substring(0, 8)}
                    </span>
                    <span className="admin-pill-tag">
                      🏷️ #{currentReq.sourceId ? currentReq.sourceId.substring(0, 10) : "src-cs-9821"}
                    </span>
                  </div>

                  <span className="admin-pill-tag-queue">
                    Ingestion Queue #01
                  </span>
                </div>

                {/* TITLE & TIMESTAMPS */}
                <h2 className="admin-source-title">
                  {currentReq.requestType === "NEW_SOURCE" ? "CrowdStrike — Falcon EDR" : `Log Source ${currentReq.sourceId}`}
                </h2>
                <div className="admin-source-subtitle">
                  <span>📅 Created: {currentReq.createdAt ? String(currentReq.createdAt).replace("T", " ").substring(0, 19) : "2026-09-04 18:22:00"}</span>
                </div>

                {/* STREAM METADATA PANEL */}
                <div className="admin-stream-metadata-box">
                  <div className="admin-stream-meta-header">
                    <div className="admin-stream-meta-title">
                      <span style={{ color: "#0d9488", fontSize: "1rem" }}>((•))</span>
                      <span>Stream Metadata</span>
                    </div>
                    <span className="admin-stream-meta-badge">
                      {logType}
                    </span>
                  </div>

                  <div className="admin-stream-meta-grid">
                    <div className="admin-meta-cell">
                      <div className="admin-meta-cell-label">LOG TYPE</div>
                      <div className="admin-meta-cell-value" style={{ color: "#0d9488" }}>{logType}</div>
                    </div>
                    <div className="admin-meta-cell">
                      <div className="admin-meta-cell-label">DELTA THRESHOLD</div>
                      <div className="admin-meta-cell-value">{isSensor ? (parsedMeta.delta || "2.5") : "N/A"}</div>
                    </div>
                    <div className="admin-meta-cell">
                      <div className="admin-meta-cell-label">MAX INTERVAL</div>
                      <div className="admin-meta-cell-value">{isSensor ? (parsedMeta.max_interval_ms || "60000") + "ms" : "60000ms"}</div>
                    </div>
                    <div className="admin-meta-cell">
                      <div className="admin-meta-cell-label">SENSOR FIELD</div>
                      <div className="admin-meta-cell-value">{isSensor ? (parsedMeta.sensor_field || "temp_celsius") : "N/A"}</div>
                    </div>
                  </div>
                </div>

                {/* MAPPING VERSION MODE SWITCHER */}
                <div className="admin-mapping-mode-switcher">
                  <button
                    onClick={() => setInspectorTab("current")}
                    className={`admin-mapping-mode-btn ${inspectorTab === "current" ? "active" : ""}`}
                    type="button"
                  >
                    Current Mapping (v2)
                  </button>
                  <button
                    onClick={() => setInspectorTab("diff")}
                    className={`admin-mapping-mode-btn ${inspectorTab === "diff" ? "active" : ""}`}
                    type="button"
                  >
                    ✨ Compare with Active (v1) Diff
                  </button>
                </div>

                {/* TAB 1: CURRENT MAPPING TABLE */}
                {inspectorTab === "current" && (
                  <div className="admin-candidate-section">
                    {/* TARGET CLICKHOUSE SCHEMA BANNER */}
                    <div style={{ backgroundColor: "#f0fdf4", border: "1px solid #bbf7d0", padding: "12px 16px", borderRadius: "12px", marginBottom: "16px", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                      <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
                        <span style={{ fontSize: "1.1rem" }}>⚡</span>
                        <div>
                          <div style={{ fontSize: "0.8125rem", fontWeight: "700", color: "#166534" }}>
                            ClickHouse Ingestion Target Schema
                          </div>
                          <div style={{ fontSize: "0.75rem", color: "#15803d" }}>
                            Target Table: <code style={{ backgroundColor: "#dcfce7", padding: "2px 6px", borderRadius: "4px", fontWeight: "700", color: "#14532d" }}>ulpf_events.canonical_events</code>
                            <span style={{ marginLeft: "8px", color: "#166534" }}>(Re-using compatible ECS schema. Zero runtime ALTER TABLE required.)</span>
                          </div>
                        </div>
                      </div>
                      <span style={{ fontSize: "0.6875rem", backgroundColor: "#166534", color: "white", padding: "3px 10px", borderRadius: "9999px", fontWeight: "600" }}>
                        Schema Re-used
                      </span>
                    </div>

                    <div className="admin-candidate-header">
                      <div className="admin-candidate-title-group">
                        <span style={{ color: "#0d9488" }}>✨</span>
                        <span className="admin-candidate-title">AI Candidate Mapping</span>
                        <span className="admin-confidence-badge">Confidence ≥ 85%</span>
                      </div>

                      {!isEditingMapping ? (
                        <button
                          onClick={startEditMode}
                          className="admin-edit-mapping-btn"
                          type="button"
                        >
                          ✏️ Edit Candidate Mapping
                        </button>
                      ) : (
                        <div style={{ display: "flex", gap: "8px" }}>
                          <button
                            onClick={cancelEditMode}
                            style={{ backgroundColor: "#64748b", color: "white", border: "none", padding: "6px 14px", borderRadius: "9999px", fontSize: "0.75rem", cursor: "pointer" }}
                            type="button"
                          >
                            Cancel
                          </button>
                          <button
                            onClick={handleSaveMapping}
                            disabled={saveMappingLoading}
                            style={{ backgroundColor: "#0d9488", color: "white", border: "none", padding: "6px 16px", borderRadius: "9999px", fontSize: "0.75rem", fontWeight: "700", cursor: "pointer" }}
                            type="button"
                          >
                            {saveMappingLoading ? "Saving…" : "💾 Save Mapping"}
                          </button>
                        </div>
                      )}
                    </div>

                    <div className="admin-mapping-table-wrapper">
                      <div className="admin-mapping-table-header">
                        <div>Raw Key</div>
                        <div>Mapped Field</div>
                        <div style={{ textAlign: "right" }}>AI Layer</div>
                      </div>

                      {(isEditingMapping ? editingRows : candidateRows).map((row, idx) => (
                        <div key={idx} className="admin-mapping-row">
                          <div className="admin-raw-key">{row.raw}</div>
                          <div>
                            {isEditingMapping ? (
                              <input
                                type="text"
                                value={row.mapped}
                                onChange={(e) => {
                                  const updated = [...editingRows];
                                  updated[idx].mapped = e.target.value;
                                  setEditingRows(updated);
                                }}
                                style={{ width: "95%", padding: "4px 8px", borderRadius: "6px", border: "1.5px solid #0d9488", fontSize: "0.8rem", fontFamily: "var(--font-mono, monospace)" }}
                              />
                            ) : (
                              <span className="admin-mapped-field" style={{ color: row.mapped === "unmapped" ? "#94a3b8" : "inherit" }}>
                                {row.mapped}
                                {row.mapped === "unmapped" && (
                                  <span style={{ fontSize: "0.6875rem", marginLeft: "8px", padding: "2px 8px", borderRadius: "4px", backgroundColor: "#f1f5f9", color: "#475569", fontWeight: "500" }}>
                                    📦 → raw_unmapped column
                                  </span>
                                )}
                              </span>
                            )}
                          </div>
                          <div className="admin-ai-layer-cell">
                            <span className="admin-ai-layer-label">{row.layer}</span>
                            <div className="admin-progress-track">
                              <div className="admin-progress-fill" style={{ width: `${row.pct || 90}%` }}></div>
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>

                    {/* RAW UNMAPPED FIELD EXPLANATORY BOX */}
                    <div style={{ marginTop: "16px", padding: "14px 18px", borderRadius: "12px", backgroundColor: "#f8fafc", border: "1px solid #e2e8f0", fontSize: "0.8rem", color: "#475569" }}>
                      <div style={{ display: "flex", alignItems: "center", gap: "8px", fontWeight: "600", color: "#0f172a", marginBottom: "4px" }}>
                        <span>📦 Unmapped Attributes & Zero Data Loss Guarantee</span>
                      </div>
                      <p style={{ margin: 0, lineHeight: "1.5" }}>
                        Any fields marked as <code style={{ backgroundColor: "#f1f5f9", padding: "2px 6px", borderRadius: "4px", color: "#0d9488" }}>unmapped</code> or extra payload attributes are captured as JSON key-value pairs (e.g. <code style={{ color: "#0f172a" }}>{"{\"user.attempt_count\": 5}"}</code>) inside the <code style={{ fontWeight: "600", color: "#0f172a" }}>raw_unmapped</code> column upon ingestion.
                      </p>
                    </div>
                  </div>
                )}

                {/* TAB 2: MULTI-VERSION DIFF VIEW */}
                {inspectorTab === "diff" && (
                  <div className="admin-candidate-section">
                    <div className="admin-candidate-header">
                      <div className="admin-candidate-title-group">
                        <span style={{ color: "#0d9488" }}>✨</span>
                        <span className="admin-candidate-title">Schema Version Diff</span>
                        <span className="admin-confidence-badge">Active (v1) → Candidate (v2)</span>
                      </div>
                    </div>

                    <div className="admin-mapping-table-wrapper">
                      <div className="admin-mapping-table-header">
                        <div>Raw Key</div>
                        <div>Active (v1)</div>
                        <div>Candidate (v2)</div>
                      </div>

                      {candidateRows.map((row, idx) => (
                        <div key={idx} className="admin-mapping-row" style={{ backgroundColor: row.updated ? "#f0fdf4" : "white" }}>
                          <div className="admin-raw-key">{row.raw}</div>
                          <div style={{ color: "#64748b", textDecoration: row.updated ? "line-through" : "none" }}>{row.v1}</div>
                          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                            <span className="admin-mapped-field">{row.mapped}</span>
                            <span style={{ fontSize: "0.6875rem", padding: "2px 8px", borderRadius: "9999px", backgroundColor: row.updated ? "#ccfbf1" : "#f1f5f9", color: row.updated ? "#0d9488" : "#64748b", fontWeight: 700 }}>
                              {row.updated ? "Updated" : "Unchanged"}
                            </span>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* APPROVAL & REJECTION ACTION BUTTONS */}
                {currentReq.status === "SUBMITTED" ? (
                  <div className="admin-action-row">
                    <button
                      onClick={() => setShowRejectModal(true)}
                      disabled={actionLoading === currentReq.requestId}
                      className="admin-btn-reject"
                      type="button"
                    >
                      <span>✕</span> Reject Request
                    </button>

                    <button
                      onClick={() => handleDecision("APPROVED")}
                      disabled={actionLoading === currentReq.requestId}
                      className="admin-btn-approve"
                      type="button"
                    >
                      <span>✔</span> {actionLoading === currentReq.requestId ? "Activating Pipeline…" : "Approve & Activate Stream"}
                    </button>
                  </div>
                ) : (
                  <div style={{ marginTop: "20px", padding: "12px", borderRadius: "12px", backgroundColor: currentReq.status === "APPROVED" ? "#f0fdf4" : "#fef2f2", color: currentReq.status === "APPROVED" ? "#166534" : "#991b1b", fontSize: "0.875rem", fontWeight: "600", textAlign: "center" }}>
                    Status: {currentReq.status} — No further action required.
                  </div>
                )}
              </div>
            ) : (
              <div className="admin-inspector-card" style={{ textAlign: "center", padding: "60px 20px", color: "#64748b" }}>
                Select an onboarding request from the queue to inspect schema mappings.
              </div>
            )
          )
        )}

        {/* REJECTION REASON OVERLAY MODAL */}
        {showRejectModal && (
          <div
            onClick={() => setShowRejectModal(false)}
            style={{ position: "fixed", inset: 0, backgroundColor: "rgba(15, 23, 42, 0.4)", backdropFilter: "blur(4px)", zIndex: 100, display: "flex", alignItems: "center", justifyContent: "center", padding: "20px" }}
          >
            <div
              onClick={(e) => e.stopPropagation()}
              style={{ backgroundColor: "white", width: "100%", maxWidth: "460px", borderRadius: "24px", padding: "28px", boxShadow: "0 20px 40px rgba(0,0,0,0.15)" }}
            >
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
                <h3 style={{ margin: 0, color: "#e11d48", fontSize: "1.15rem", fontWeight: "800" }}>
                  Reject Onboarding Request
                </h3>
                <button onClick={() => setShowRejectModal(false)} style={{ background: "none", border: "none", fontSize: "1.2rem", cursor: "pointer", color: "#64748b" }}>
                  ✕
                </button>
              </div>

              <label style={{ display: "block", fontSize: "0.85rem", fontWeight: "700", marginBottom: "8px", color: "#0f172a" }}>
                Rejection Reason / Feedback Note for Vendor (Optional):
              </label>

              <textarea
                rows={4}
                value={rejectFeedback}
                onChange={(e) => setRejectFeedback(e.target.value)}
                placeholder="Optional: Explain what needs to change before resubmission (e.g. missing timestamp format, unmapped auth tokens)..."
                style={{ width: "100%", padding: "12px", borderRadius: "12px", border: "1.5px solid #cbd5e1", fontFamily: "inherit", fontSize: "0.875rem", marginBottom: "8px" }}
              />

              <div style={{ display: "flex", justifyContent: "space-between", fontSize: "0.75rem", color: "#64748b", marginBottom: "20px" }}>
                <span>Vendor will be notified with this note if provided.</span>
                <span>{rejectFeedback.length} chars</span>
              </div>

              <div style={{ display: "flex", justifyContent: "flex-end", gap: "10px" }}>
                <button
                  onClick={() => setShowRejectModal(false)}
                  style={{ padding: "10px 20px", borderRadius: "9999px", border: "none", backgroundColor: "#f1f5f9", color: "#475569", fontWeight: "700", cursor: "pointer" }}
                  type="button"
                >
                  Cancel
                </button>
                <button
                  onClick={() => handleDecision("REJECTED", rejectFeedback)}
                  style={{ padding: "10px 20px", borderRadius: "9999px", border: "none", backgroundColor: "#e11d48", color: "white", fontWeight: "700", cursor: "pointer", opacity: 1 }}
                  type="button"
                >
                  Confirm Rejection
                </button>
              </div>
            </div>
          </div>
        )}

        {/* TOAST PILL NOTIFICATION */}
        {toastMessage && (
          <div style={{ position: "fixed", bottom: "30px", left: "50%", transform: "translateX(-50%)", backgroundColor: "#0f172a", color: "white", padding: "12px 24px", borderRadius: "9999px", boxShadow: "0 10px 30px rgba(0,0,0,0.2)", fontSize: "0.875rem", fontWeight: "700", zIndex: 200, display: "flex", alignItems: "center", gap: "8px" }}>
            <span>✨ {toastMessage}</span>
          </div>
        )}
      </main>
    </div>
  );
}

export default AdminDashboardPage;
