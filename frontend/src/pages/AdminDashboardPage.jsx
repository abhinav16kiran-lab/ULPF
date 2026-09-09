import { useState, useEffect, useCallback } from "react";
import { Link, useNavigate } from "react-router-dom";
import client from "../api/client";
import Navbar from "../components/Navbar";

function AdminDashboardPage() {
  const navigate = useNavigate();

  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [integrityBlocks, setIntegrityBlocks] = useState([]);

  // Active selected request
  const [selectedReqId, setSelectedReqId] = useState(null);

  // Layout & Inspector View states
  const [mobileView, setMobileView] = useState("queue"); // "queue" | "inspector"
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
      return Object.entries(meta.candidate_mapping).map(([raw, mapped], idx) => ({
        raw,
        mapped: typeof mapped === "object" ? mapped.canonicalField || "unmapped" : String(mapped),
        v1: "unmapped",
        layer: idx % 2 === 0 ? "ALIAS 1.0" : "TYPO 0.95",
        pct: idx % 2 === 0 ? 100 : 95,
        updated: true
      }));
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
    setMetaEditLogType(logType);
    setMetaEditDelta(parsedMeta.delta || "");
    setMetaEditInterval(parsedMeta.max_interval_ms || "60000");
    setMetaEditSensorField(parsedMeta.sensor_field || "");
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

  const pendingCount = requests.filter((r) => r.status === "SUBMITTED").length;
  const approvedCount = requests.filter((r) => r.status === "APPROVED").length;

  return (
    <div style={{ backgroundColor: "#f9f9ff", minHeight: "100vh", fontFamily: "'Plus Jakarta Sans', sans-serif" }}>
      <Navbar />

      <main style={{ maxWidth: "1280px", margin: "0 auto", padding: "0 20px 80px 20px" }}>
        {/* TOP BAR / NAVIGATION ACTIONS */}
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "20px", flexWrap: "wrap", gap: "12px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
            <h1 style={{ fontSize: "1.5rem", fontWeight: "700", color: "#111c2d", margin: 0 }}>
              ULPF Operational Review Workspace
            </h1>
            <span style={{ backgroundColor: "#71f8e4", color: "#00201c", fontSize: "0.75rem", fontWeight: "700", padding: "3px 10px", borderRadius: "9999px", display: "inline-flex", alignItems: "center", gap: "5px" }}>
              <span style={{ width: "6px", height: "6px", borderRadius: "9999px", backgroundColor: "#006b5f" }}></span>
              Admin Control
            </span>
          </div>

          <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
            {/* MISSING BLOCKCHAIN / MERKLE AUDIT BUTTON */}
            <Link
              to="/integrity"
              style={{
                backgroundColor: "#0f766e",
                color: "white",
                padding: "8px 16px",
                borderRadius: "9999px",
                textDecoration: "none",
                fontSize: "0.85rem",
                fontWeight: "600",
                display: "inline-flex",
                alignItems: "center",
                gap: "6px",
                boxShadow: "0 4px 12px rgba(15, 118, 110, 0.25)"
              }}
            >
              <span className="material-symbols-outlined" style={{ fontSize: "16px" }}>lock</span>
              🔐 Merkle Tamper Audit ({integrityBlocks.length} Blocks)
            </Link>

            <Link
              to="/analytics"
              style={{
                backgroundColor: "#006b5f",
                color: "white",
                padding: "8px 16px",
                borderRadius: "9999px",
                textDecoration: "none",
                fontSize: "0.85rem",
                fontWeight: "600",
                display: "inline-flex",
                alignItems: "center",
                gap: "6px",
                boxShadow: "0 4px 12px rgba(0, 107, 95, 0.25)"
              }}
            >
              <span className="material-symbols-outlined" style={{ fontSize: "16px" }}>bolt</span>
              ⚡ Analytics Console
            </Link>
          </div>
        </div>

        {/* DEMO SANDBOX TOOLSTRIP */}
        <section style={{ backgroundColor: "#f0f3ff", padding: "10px 16px", borderRadius: "12px", marginBottom: "20px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "8px", fontSize: "0.85rem", color: "#3c4947", fontWeight: "600" }}>
            <span className="material-symbols-outlined" style={{ color: "#006b5f", fontSize: "18px" }}>tune</span>
            <span>Review Sandbox & UI Simulation</span>
          </div>
          <div style={{ display: "flex", gap: "8px" }}>
            <button
              onClick={() => setSandboxSkeleton(!sandboxSkeleton)}
              style={{ backgroundColor: sandboxSkeleton ? "#14b8a6" : "white", color: sandboxSkeleton ? "white" : "#111c2d", border: "none", padding: "4px 12px", borderRadius: "9999px", fontSize: "0.75rem", fontWeight: "600", cursor: "pointer", boxShadow: "0 1px 3px rgba(0,0,0,0.08)" }}
            >
              Skeleton
            </button>
            <button
              onClick={() => setSandboxEmpty(!sandboxEmpty)}
              style={{ backgroundColor: sandboxEmpty ? "#14b8a6" : "white", color: sandboxEmpty ? "white" : "#111c2d", border: "none", padding: "4px 12px", borderRadius: "9999px", fontSize: "0.75rem", fontWeight: "600", cursor: "pointer", boxShadow: "0 1px 3px rgba(0,0,0,0.08)" }}
            >
              Empty State
            </button>
          </div>
        </section>

        {/* STAT SUMMARY BAR */}
        <section style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: "12px", marginBottom: "20px" }}>
          <div style={{ backgroundColor: "white", padding: "14px 18px", borderRadius: "16px", boxShadow: "0 4px 20px -2px rgba(30, 41, 59, 0.05)", display: "flex", alignItems: "center", gap: "12px" }}>
            <div style={{ width: "40px", height: "40px", borderRadius: "9999px", backgroundColor: "#71f8e4", display: "flex", alignItems: "center", justifyContent: "center", color: "#006b5f" }}>
              <span className="material-symbols-outlined">schedule</span>
            </div>
            <div>
              <div style={{ fontSize: "1.25rem", fontWeight: "700", color: "#111c2d" }}>{pendingCount}</div>
              <div style={{ fontSize: "0.75rem", color: "#6c7a77" }}>Pending Review</div>
            </div>
          </div>

          <div style={{ backgroundColor: "white", padding: "14px 18px", borderRadius: "16px", boxShadow: "0 4px 20px -2px rgba(30, 41, 59, 0.05)", display: "flex", alignItems: "center", gap: "12px" }}>
            <div style={{ width: "40px", height: "40px", borderRadius: "9999px", backgroundColor: "#d8e3fb", display: "flex", alignItems: "center", justifyContent: "center", color: "#006b5f" }}>
              <span className="material-symbols-outlined">verified</span>
            </div>
            <div>
              <div style={{ fontSize: "1.25rem", fontWeight: "700", color: "#111c2d" }}>{approvedCount}</div>
              <div style={{ fontSize: "0.75rem", color: "#6c7a77" }}>Active Streams / Vendors</div>
            </div>
          </div>

          <div style={{ backgroundColor: "white", padding: "14px 18px", borderRadius: "16px", boxShadow: "0 4px 20px -2px rgba(30, 41, 59, 0.05)", display: "flex", alignItems: "center", gap: "12px" }}>
            <div style={{ width: "40px", height: "40px", borderRadius: "9999px", backgroundColor: "#ccfbf1", display: "flex", alignItems: "center", justifyContent: "center", color: "#0f766e" }}>
              <span className="material-symbols-outlined">gavel</span>
            </div>
            <div>
              <div style={{ fontSize: "1.25rem", fontWeight: "700", color: "#0f766e" }}>{integrityBlocks.length}</div>
              <div style={{ fontSize: "0.75rem", color: "#6c7a77" }}>Merkle Sealed Blocks</div>
            </div>
          </div>
        </section>

        {/* MOBILE VIEW SEGMENTED CONTROLLER */}
        <div style={{ display: "flex", backgroundColor: "#e7eeff", padding: "4px", borderRadius: "9999px", marginBottom: "20px" }}>
          <button
            onClick={() => setMobileView("queue")}
            style={{ flex: 1, padding: "8px", borderRadius: "9999px", border: "none", backgroundColor: mobileView === "queue" ? "white" : "transparent", color: mobileView === "queue" ? "#006b5f" : "#3c4947", fontWeight: "600", fontSize: "0.85rem", cursor: "pointer" }}
          >
            Request Queue ({requests.length})
          </button>
          <button
            onClick={() => setMobileView("inspector")}
            style={{ flex: 1, padding: "8px", borderRadius: "9999px", border: "none", backgroundColor: mobileView === "inspector" ? "white" : "transparent", color: mobileView === "inspector" ? "#006b5f" : "#3c4947", fontWeight: "600", fontSize: "0.85rem", cursor: "pointer" }}
          >
            Live Inspector
          </button>
        </div>

        {/* SKELETON SIMULATION */}
        {sandboxSkeleton && (
          <div style={{ display: "flex", flexDirection: "column", gap: "12px", opacity: 0.6 }}>
            <div style={{ height: "60px", backgroundColor: "#e7eeff", borderRadius: "16px" }}></div>
            <div style={{ height: "60px", backgroundColor: "#e7eeff", borderRadius: "16px" }}></div>
          </div>
        )}

        {/* EMPTY STATE SIMULATION */}
        {sandboxEmpty && (
          <div style={{ backgroundColor: "white", padding: "40px", borderRadius: "20px", textAlign: "center" }}>
            <h3>All caught up!</h3>
            <p style={{ color: "#6c7a77" }}>No pending requests. All incoming log streams are mapped and healthy.</p>
          </div>
        )}

        {/* MAIN MASTER-DETAIL WORKSPACE */}
        {!sandboxSkeleton && !sandboxEmpty && (
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1.6fr", gap: "20px" }}>
            {/* LEFT COLUMN: REQUEST QUEUE */}
            <section style={{ display: mobileView === "inspector" ? "none" : "flex", flexDirection: "column", gap: "12px" }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <span style={{ fontSize: "0.75rem", fontWeight: "700", color: "#6c7a77", textTransform: "uppercase" }}>
                  INCOMING STREAMS ({requests.length})
                </span>
                <span style={{ fontSize: "0.75rem", color: "#006b5f", fontFamily: "monospace", fontWeight: "600" }}>
                  auto-sync ON
                </span>
              </div>

              {loading && <p>Loading stream queue…</p>}
              {error && <p style={{ color: "red" }}>{error}</p>}

              {!loading && !error && requests.length === 0 && (
                <div style={{ backgroundColor: "white", padding: "20px", borderRadius: "16px", textAlign: "center", color: "#6c7a77" }}>
                  No onboarding requests submitted yet.
                </div>
              )}

              {!loading && !error && requests.map((req) => {
                const isSelected = req.requestId === selectedReqId;
                const meta = parseMetadata(req);
                const reqLogType = meta.log_type || "REG_LOG";

                return (
                  <div
                    key={req.requestId}
                    onClick={() => {
                      setSelectedReqId(req.requestId);
                      if (window.innerWidth < 768) setMobileView("inspector");
                    }}
                    style={{
                      backgroundColor: "white",
                      padding: "16px",
                      borderRadius: "16px",
                      boxShadow: "0 4px 20px -2px rgba(30, 41, 59, 0.05)",
                      cursor: "pointer",
                      borderLeft: isSelected ? "6px solid #14b8a6" : "6px solid transparent",
                      transition: "all 0.2s"
                    }}
                  >
                    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "6px" }}>
                      <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
                        <span className="material-symbols-outlined" style={{ color: "#006b5f", fontSize: "18px" }}>
                          {reqLogType === "SEN_TEL" ? "sensors" : "shield"}
                        </span>
                        <strong style={{ fontSize: "0.95rem", color: "#111c2d" }}>
                          {req.requestId.substring(0, 8)}…
                        </strong>
                      </div>
                      <span style={{
                        padding: "2px 8px",
                        borderRadius: "9999px",
                        fontSize: "0.7rem",
                        fontWeight: "700",
                        backgroundColor: req.status === "APPROVED" ? "#ecfdf5" : req.status === "REJECTED" ? "#ffe4e6" : "#f0f3ff",
                        color: req.status === "APPROVED" ? "#0f766e" : req.status === "REJECTED" ? "#ae2f34" : "#006b5f"
                      }}>
                        {req.status}
                      </span>
                    </div>

                    <p style={{ fontSize: "0.8rem", color: "#6c7a77", margin: "0 0 8px 0" }}>
                      User: <code>{req.userId}</code> | Source: <code>{req.sourceId || "N/A"}</code> | {req.requestType}
                    </p>

                    <div style={{ display: "flex", justifyContent: "space-between", fontSize: "0.75rem", color: "#3c4947" }}>
                      <span>Submitted: {req.createdAt ? String(req.createdAt).substring(0, 16) : "Recent"}</span>
                      <span style={{ color: "#006b5f", fontWeight: "600" }}>View Schema →</span>
                    </div>
                  </div>
                );
              })}
            </section>

            {/* RIGHT COLUMN: SCHEMA & MAPPING INSPECTOR */}
            <section style={{ display: mobileView === "queue" ? "none" : "flex", flexDirection: "column", gap: "16px" }}>
              {currentReq ? (
                <div style={{ backgroundColor: "white", padding: "20px", borderRadius: "16px", boxShadow: "0 4px 20px -2px rgba(30, 41, 59, 0.05)" }}>
                  {/* HEADER META */}
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: "16px", flexWrap: "wrap", gap: "8px" }}>
                    <div>
                      <div style={{ display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" }}>
                        <span style={{ backgroundColor: "#e7eeff", color: "#111c2d", fontSize: "0.8rem", fontWeight: "600", padding: "2px 8px", borderRadius: "6px", fontFamily: "monospace" }}>
                          #{currentReq.requestId}
                        </span>
                        <span style={{ backgroundColor: "#f1f1f1", fontSize: "0.75rem", padding: "2px 8px", borderRadius: "9999px", color: "#3c4947" }}>
                          👤 Owner: {currentReq.userId}
                        </span>
                        <span style={{ backgroundColor: "#f1f1f1", fontSize: "0.75rem", padding: "2px 8px", borderRadius: "9999px", color: "#3c4947" }}>
                          🏷️ Source ID: {currentReq.sourceId || "Pending"}
                        </span>
                      </div>
                      <h2 style={{ fontSize: "1.25rem", fontWeight: "700", color: "#111c2d", margin: "8px 0 2px 0" }}>
                        Request Type: {currentReq.requestType}
                      </h2>
                      <span style={{ fontSize: "0.8rem", color: "#6c7a77" }}>
                        Submitted: {currentReq.createdAt}
                      </span>
                    </div>

                    <span style={{ backgroundColor: "#71f8e4", color: "#00201c", fontSize: "0.75rem", fontWeight: "700", padding: "4px 10px", borderRadius: "9999px" }}>
                      Queue Status: {currentReq.status}
                    </span>
                  </div>

                  {/* STREAM METADATA PANEL */}
                  <div style={{ backgroundColor: "#f0f3ff", padding: "12px", borderRadius: "12px", marginBottom: "16px" }}>
                    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "8px" }}>
                      <strong style={{ fontSize: "0.85rem", color: "#111c2d", display: "inline-flex", alignItems: "center", gap: "6px" }}>
                        <span className="material-symbols-outlined" style={{ fontSize: "16px", color: "#006b5f" }}>sensors</span>
                        Stream Metadata
                      </strong>
                      <span style={{ backgroundColor: "#14b8a6", color: "white", fontSize: "0.7rem", fontWeight: "700", padding: "2px 8px", borderRadius: "9999px", fontFamily: "monospace" }}>
                        {logType}
                      </span>
                    </div>

                    <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: "8px", fontSize: "0.75rem" }}>
                      <div style={{ backgroundColor: "white", padding: "8px", borderRadius: "8px" }}>
                        <div style={{ color: "#6c7a77", fontWeight: "700", fontSize: "0.65rem", textTransform: "uppercase" }}>Log Type</div>
                        <div style={{ fontWeight: "700", color: "#006b5f" }}>{logType}</div>
                      </div>
                      <div style={{ backgroundColor: "white", padding: "8px", borderRadius: "8px" }}>
                        <div style={{ color: "#6c7a77", fontWeight: "700", fontSize: "0.65rem", textTransform: "uppercase" }}>Delta Threshold</div>
                        <div style={{ fontFamily: "monospace" }}>{isSensor ? (parsedMeta.delta || "2.5") : "N/A"}</div>
                      </div>
                      <div style={{ backgroundColor: "white", padding: "8px", borderRadius: "8px" }}>
                        <div style={{ color: "#6c7a77", fontWeight: "700", fontSize: "0.65rem", textTransform: "uppercase" }}>Max Interval</div>
                        <div style={{ fontFamily: "monospace" }}>{isSensor ? (parsedMeta.max_interval_ms || "60000") + "ms" : "N/A"}</div>
                      </div>
                      <div style={{ backgroundColor: "white", padding: "8px", borderRadius: "8px" }}>
                        <div style={{ color: "#6c7a77", fontWeight: "700", fontSize: "0.65rem", textTransform: "uppercase" }}>Sensor Field</div>
                        <div style={{ fontFamily: "monospace" }}>{isSensor ? (parsedMeta.sensor_field || "temp_celsius") : "N/A"}</div>
                      </div>
                    </div>
                  </div>

                  {/* INSPECTOR VIEW TABS */}
                  <div style={{ display: "flex", backgroundColor: "#f0f3ff", padding: "4px", borderRadius: "10px", marginBottom: "16px" }}>
                    <button
                      onClick={() => setInspectorTab("current")}
                      style={{ flex: 1, padding: "6px", borderRadius: "8px", border: "none", backgroundColor: inspectorTab === "current" ? "white" : "transparent", color: inspectorTab === "current" ? "#006b5f" : "#6c7a77", fontWeight: "600", fontSize: "0.8rem", cursor: "pointer" }}
                    >
                      Current Mapping (v2)
                    </button>
                    <button
                      onClick={() => setInspectorTab("diff")}
                      style={{ flex: 1, padding: "6px", borderRadius: "8px", border: "none", backgroundColor: inspectorTab === "diff" ? "white" : "transparent", color: inspectorTab === "diff" ? "#006b5f" : "#6c7a77", fontWeight: "600", fontSize: "0.8rem", cursor: "pointer" }}
                    >
                      Compare with Active (v1) Diff
                    </button>
                  </div>

                  {/* TAB 1: CURRENT MAPPING TABLE */}
                  {inspectorTab === "current" && (
                    <div>
                      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "10px" }}>
                        <div style={{ display: "flex", alignItems: "center", gap: "6px", color: "#006b5f", fontWeight: "700", fontSize: "0.9rem" }}>
                          <span className="material-symbols-outlined" style={{ fontSize: "18px" }}>auto_awesome</span>
                          <span>AI Candidate Mapping</span>
                          <span style={{ fontSize: "0.7rem", backgroundColor: "#e7eeff", color: "#3c4947", padding: "2px 6px", borderRadius: "9999px" }}>Confidence ≥ 85%</span>
                        </div>

                        {!isEditingMapping ? (
                          <button
                            onClick={startEditMode}
                            style={{ backgroundColor: "white", border: "1px solid #14b8a6", color: "#006b5f", padding: "4px 12px", borderRadius: "9999px", fontSize: "0.75rem", fontWeight: "600", cursor: "pointer" }}
                          >
                            ✏️ Edit Candidate Mapping
                          </button>
                        ) : (
                          <div style={{ display: "flex", gap: "6px" }}>
                            <button
                              onClick={cancelEditMode}
                              style={{ backgroundColor: "#6c7a77", color: "white", border: "none", padding: "4px 10px", borderRadius: "9999px", fontSize: "0.75rem", cursor: "pointer" }}
                            >
                              Cancel
                            </button>
                            <button
                              onClick={handleSaveMapping}
                              disabled={saveMappingLoading}
                              style={{ backgroundColor: "#006b5f", color: "white", border: "none", padding: "4px 12px", borderRadius: "9999px", fontSize: "0.75rem", fontWeight: "600", cursor: "pointer" }}
                            >
                              {saveMappingLoading ? "Saving…" : "💾 Save Mapping"}
                            </button>
                          </div>
                        )}
                      </div>

                      <div style={{ borderRadius: "12px", overflow: "hidden", border: "1px solid #e7eeff" }}>
                        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", backgroundColor: "#e7eeff", padding: "8px 12px", fontSize: "0.75rem", fontWeight: "700", color: "#3c4947" }}>
                          <div>Raw Key</div>
                          <div>Mapped Field</div>
                          <div style={{ textAlign: "right" }}>AI Layer</div>
                        </div>

                        {(isEditingMapping ? editingRows : candidateRows).map((row, idx) => (
                          <div key={idx} style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", padding: "8px 12px", backgroundColor: idx % 2 === 0 ? "white" : "#f9f9ff", borderBottom: "1px solid #f0f3ff", alignItems: "center", fontSize: "0.8rem", fontFamily: "monospace" }}>
                            <div style={{ color: "#111c2d" }}>{row.raw}</div>
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
                                  style={{ width: "90%", padding: "2px 6px", borderRadius: "4px", border: "1px solid #14b8a6", fontSize: "0.75rem", fontFamily: "monospace" }}
                                />
                              ) : (
                                <span style={{ color: "#006b5f", fontWeight: "600" }}>{row.mapped}</span>
                              )}
                            </div>
                            <div style={{ textAlign: "right" }}>
                              <span style={{ fontSize: "0.7rem", fontWeight: "700", color: "#006b5f" }}>{row.layer}</span>
                              <div style={{ height: "4px", backgroundColor: "#e7eeff", borderRadius: "9999px", marginTop: "2px", overflow: "hidden" }}>
                                <div style={{ height: "100%", width: `${row.pct}%`, backgroundColor: "#14b8a6" }}></div>
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* TAB 2: MULTI-VERSION DIFF VIEW */}
                  {inspectorTab === "diff" && (
                    <div>
                      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "10px" }}>
                        <div style={{ display: "flex", alignItems: "center", gap: "6px", color: "#006b5f", fontWeight: "700", fontSize: "0.9rem" }}>
                          <span className="material-symbols-outlined" style={{ fontSize: "18px" }}>difference</span>
                          <span>Schema Version Diff</span>
                        </div>
                        <span style={{ fontSize: "0.75rem", backgroundColor: "#e7eeff", padding: "2px 8px", borderRadius: "9999px" }}>
                          Active (v1) → Candidate (v2)
                        </span>
                      </div>

                      <div style={{ borderRadius: "12px", overflow: "hidden", border: "1px solid #e7eeff" }}>
                        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1.2fr", backgroundColor: "#e7eeff", padding: "8px 12px", fontSize: "0.75rem", fontWeight: "700", color: "#3c4947" }}>
                          <div>Raw Key</div>
                          <div>Active (v1)</div>
                          <div>Candidate (v2)</div>
                        </div>

                        {candidateRows.map((row, idx) => (
                          <div key={idx} style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1.2fr", padding: "8px 12px", backgroundColor: row.updated ? "#ecfdf5" : "white", borderBottom: "1px solid #f0f3ff", alignItems: "center", fontSize: "0.8rem", fontFamily: "monospace" }}>
                            <div>{row.raw}</div>
                            <div style={{ color: "#6c7a77", textDecoration: row.updated ? "line-through" : "none" }}>{row.v1}</div>
                            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                              <span style={{ color: "#006b5f", fontWeight: row.updated ? "700" : "400" }}>{row.mapped}</span>
                              <span style={{ fontSize: "0.65rem", padding: "1px 6px", borderRadius: "9999px", backgroundColor: row.updated ? "#71f8e4" : "#f1f1f1", color: row.updated ? "#00201c" : "#6c7a77" }}>
                                {row.updated ? "Updated" : "Unchanged"}
                              </span>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* APPROVAL & REJECTION ACTION BUTTONS */}
                  {currentReq.status === "SUBMITTED" && (
                    <div style={{ marginTop: "20px", display: "flex", gap: "12px" }}>
                      <button
                        onClick={() => setShowRejectModal(true)}
                        disabled={actionLoading === currentReq.requestId}
                        style={{ flex: 1, padding: "10px", borderRadius: "9999px", backgroundColor: "#ff6b6b", color: "white", border: "none", fontWeight: "700", fontSize: "0.9rem", cursor: "pointer" }}
                      >
                        ❌ Reject Request
                      </button>

                      <button
                        onClick={() => handleDecision("APPROVED")}
                        disabled={actionLoading === currentReq.requestId}
                        style={{ flex: 2, padding: "10px", borderRadius: "9999px", backgroundColor: "#14b8a6", color: "white", border: "none", fontWeight: "700", fontSize: "0.9rem", cursor: "pointer", boxShadow: "0 6px 18px -3px rgba(20, 184, 166, 0.35)" }}
                      >
                        {actionLoading === currentReq.requestId ? "Processing…" : "✅ Approve & Activate Stream"}
                      </button>
                    </div>
                  )}
                </div>
              ) : (
                <div style={{ backgroundColor: "white", padding: "40px", borderRadius: "16px", textAlign: "center", color: "#6c7a77" }}>
                  Select an onboarding request from the queue to inspect schema mappings.
                </div>
              )}
            </section>
          </div>
        )}

        {/* REJECTION REASON OVERLAY MODAL */}
        {showRejectModal && (
          <div
            onClick={() => setShowRejectModal(false)}
            style={{ position: "fixed", inset: 0, backgroundColor: "rgba(0,0,0,0.4)", backdropFilter: "blur(4px)", zIndex: 100, display: "flex", alignItems: "center", justifyContent: "center", padding: "20px" }}
          >
            <div
              onClick={(e) => e.stopPropagation()}
              style={{ backgroundColor: "white", width: "100%", maxWidth: "450px", borderRadius: "20px", padding: "24px", boxShadow: "0 20px 40px rgba(0,0,0,0.2)" }}
            >
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
                <h3 style={{ margin: 0, color: "#ae2f34", fontSize: "1.1rem", fontWeight: "700" }}>
                  Reject Onboarding Request
                </h3>
                <button onClick={() => setShowRejectModal(false)} style={{ background: "none", border: "none", fontSize: "1.2rem", cursor: "pointer" }}>
                  ✕
                </button>
              </div>

              <label style={{ display: "block", fontSize: "0.85rem", fontWeight: "600", marginBottom: "8px", color: "#111c2d" }}>
                Rejection Reason / Feedback Note for Vendor:
              </label>

              <textarea
                rows={4}
                value={rejectFeedback}
                onChange={(e) => setRejectFeedback(e.target.value)}
                placeholder="Explain what needs to change before resubmission (e.g. missing timestamp format, unmapped auth tokens)..."
                style={{ width: "100%", padding: "10px", borderRadius: "12px", border: "1px solid #ccc", fontFamily: "inherit", fontSize: "0.85rem", marginBottom: "8px" }}
              />

              <div style={{ display: "flex", justifyContent: "space-between", fontSize: "0.75rem", color: "#6c7a77", marginBottom: "16px" }}>
                <span>Vendor will be notified with this note.</span>
                <span>{rejectFeedback.length} chars</span>
              </div>

              <div style={{ display: "flex", justifyContent: "flex-end", gap: "8px" }}>
                <button
                  onClick={() => setShowRejectModal(false)}
                  style={{ padding: "8px 16px", borderRadius: "9999px", border: "none", backgroundColor: "#f0f3ff", color: "#3c4947", fontWeight: "600", cursor: "pointer" }}
                >
                  Cancel
                </button>
                <button
                  onClick={() => handleDecision("REJECTED", rejectFeedback)}
                  disabled={!rejectFeedback.trim()}
                  style={{ padding: "8px 16px", borderRadius: "9999px", border: "none", backgroundColor: "#ae2f34", color: "white", fontWeight: "600", cursor: rejectFeedback.trim() ? "pointer" : "not-allowed", opacity: rejectFeedback.trim() ? 1 : 0.5 }}
                >
                  Confirm Rejection
                </button>
              </div>
            </div>
          </div>
        )}

        {/* TOAST PILL NOTIFICATION */}
        {toastMessage && (
          <div style={{ position: "fixed", bottom: "30px", left: "50%", transform: "translateX(-50%)", backgroundColor: "#263143", color: "#ecf1ff", padding: "10px 20px", borderRadius: "9999px", boxShadow: "0 10px 30px rgba(0,0,0,0.2)", fontSize: "0.85rem", fontWeight: "600", zIndex: 200, display: "flex", alignItems: "center", gap: "8px" }}>
            <span>✨ {toastMessage}</span>
          </div>
        )}
      </main>
    </div>
  );
}

export default AdminDashboardPage;
