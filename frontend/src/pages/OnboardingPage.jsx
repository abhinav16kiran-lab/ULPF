import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import client from "../api/client";
import Navbar from "../components/Navbar";
import "./OnboardingPage.css";

function OnboardingPage() {
  const navigate = useNavigate();

  const [onboardMode, setOnboardMode] = useState("NEW"); // "NEW" | "UPDATE"
  const [selectedSourceId, setSelectedSourceId] = useState("");
  const [vendorName, setVendorName] = useState("");
  const [sourceName, setSourceName] = useState("");
  const [sourceType, setSourceType] = useState("");
  const [logType, setLogType] = useState("REG_LOG");
  const [delta, setDelta] = useState("");
  const [maxIntervalMs, setMaxIntervalMs] = useState("60000");
  const [sensorField, setSensorField] = useState("");
  const [sampleLogFile, setSampleLogFile] = useState(null);
  const [schemaFile, setSchemaFile] = useState(null);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [showModal, setShowModal] = useState(false);
  const [copySuccess, setCopySuccess] = useState(false);

  // Tabs state for history
  const [historyTab, setHistoryTab] = useState("requests"); // "requests" | "active"

  // User requests & sources state
  const [myRequests, setMyRequests] = useState([]);
  const [mySources, setMySources] = useState([]);
  const [myRequestsLoading, setMyRequestsLoading] = useState(false);

  const username = localStorage.getItem("username");

  useEffect(() => {
    if (!username) {
      navigate("/login");
    }
  }, [username, navigate]);

  const fetchUserData = useCallback(async () => {
    if (!username) return;
    setMyRequestsLoading(true);
    try {
      const [reqRes, srcRes] = await Promise.all([
        client.get("/v1/onboard/my-requests"),
        client.get("/v1/onboard/my-sources")
      ]);
      setMyRequests(reqRes.data.requests || []);
      setMySources(srcRes.data.sources || []);
    } catch {
      // Ignore fetch errors if user has no sources yet
    } finally {
      setMyRequestsLoading(false);
    }
  }, [username]);

  useEffect(() => {
    fetchUserData();
  }, [fetchUserData]);

  async function handleSubmit(e) {
    e.preventDefault();
    setError(null);
    setResult(null);
    setLoading(true);

    const formData = new FormData();

    if (onboardMode === "UPDATE") {
      if (!selectedSourceId) {
        setError("Please select an active log source to update.");
        setLoading(false);
        return;
      }
      formData.append("sourceId", selectedSourceId);
    } else {
      formData.append("vendorName", vendorName);
      formData.append("sourceName", sourceName);
    }

    formData.append("sourceType", sourceType);
    formData.append("logType", logType);

    if (logType === "SEN_TEL") {
      if (delta) formData.append("delta", delta);
      if (maxIntervalMs) formData.append("maxIntervalMs", maxIntervalMs);
      if (sensorField) formData.append("sensorField", sensorField);
    }

    if (sampleLogFile) {
      formData.append("sampleLogFile", sampleLogFile);
    }
    if (schemaFile) {
      formData.append("schemaFile", schemaFile);
    }

    try {
      const endpoint = onboardMode === "UPDATE"
        ? `/v1/onboard/update/${username}`
        : `/v1/onboard/${username}`;

      const response = await client.post(endpoint, formData);

      setResult({
        requestId: response.data.requestId,
        sourceId: response.data.sourceId,
        vendorId: response.data.vendorId,
        apiKey: response.data.apiKey || null,
        status: response.data.status,
        message: response.data.message,
      });

      // Clear form & refetch user data
      setSelectedSourceId("");
      setVendorName("");
      setSourceName("");
      setSourceType("");
      setLogType("REG_LOG");
      setDelta("");
      setMaxIntervalMs("60000");
      setSensorField("");
      setSampleLogFile(null);
      setSchemaFile(null);
      await fetchUserData();

      // Show success modal
      setShowModal(true);

    } catch (err) {
      if (err.response && err.response.data) {
        if (typeof err.response.data === "string") {
          setError(err.response.data);
        } else if (err.response.data.error) {
          setError(err.response.data.error);
        } else {
          setError(JSON.stringify(err.response.data));
        }
      } else {
        setError(err.message);
      }
    } finally {
      setLoading(false);
    }
  }

  const isSensorMode = logType === "SEN_TEL";
  const toggleSensorMode = () => {
    setLogType(isSensorMode ? "REG_LOG" : "SEN_TEL");
  };

  const copyModalApiKey = () => {
    if (navigator.clipboard && result?.apiKey) {
      navigator.clipboard.writeText(result.apiKey);
      setCopySuccess(true);
      setTimeout(() => setCopySuccess(false), 2000);
    }
  };

  if (!username) {
    return null;
  }

  const ACCEPTED_FILE_TYPES = ".log,.csv,.json,.txt";

  const renderFormatSnippet = (fmt) => {
    switch (fmt) {
      case "JSON":
        return (
          <>
            <span className="text-slate-500 select-none">1 </span>{"{\n"}
            <span className="text-slate-500 select-none">2 </span>  <span className="text-teal-400">"timestamp"</span>: <span className="text-amber-300">"2026-09-04T12:00:00Z"</span>,{"\n"}
            <span className="text-slate-500 select-none">3 </span>  <span className="text-teal-400">"src_ip"</span>: <span className="text-emerald-300">"192.168.1.50"</span>,{"\n"}
            <span className="text-slate-500 select-none">4 </span>  <span className="text-teal-400">"dest_ip"</span>: <span className="text-emerald-300">"10.0.4.120"</span>,{"\n"}
            <span className="text-slate-500 select-none">5 </span>  <span className="text-teal-400">"action"</span>: <span className="text-rose-400">"BLOCK"</span>,{"\n"}
            <span className="text-slate-500 select-none">6 </span>  <span className="text-teal-400">"vendor"</span>: <span className="text-amber-300">"CrowdStrike Falcon"</span>,{"\n"}
            <span className="text-slate-500 select-none">7 </span>  <span className="text-teal-400">"rule_id"</span>: <span className="text-indigo-300">"SEC_SURGE_99"</span>{"\n"}
            <span className="text-slate-500 select-none">8 </span>{"}"}
          </>
        );
      case "SYSLOG":
        return (
          <>
            <span className="text-slate-500 select-none">1 </span>{"<134>1 2026-09-04T12:00:00Z falcon.host.corp CS-EDR - -"}{"\n"}
            <span className="text-slate-500 select-none">2 </span>{"[meta src=\"192.168.1.50\" dst=\"10.0.4.120\" action=\"BLOCK\"]"}{"\n"}
            <span className="text-slate-500 select-none">3 </span>{"Process injection attempt halted on PID 4410"}
          </>
        );
      case "CEF":
        return (
          <>
            <span className="text-slate-500 select-none">1 </span>{"CEF:0|CrowdStrike|Falcon|1.0|SEC_SURGE_99|Malicious Process|7|"}{"\n"}
            <span className="text-slate-500 select-none">2 </span>{"src=192.168.1.50 dst=10.0.4.120 act=BLOCK"}{"\n"}
            <span className="text-slate-500 select-none">3 </span>{"msg=Process terminated cleanly by kernel driver"}
          </>
        );
      case "CSV":
        return (
          <>
            <span className="text-slate-500 select-none">1 </span>{"timestamp,src_ip,dest_ip,action,rule_id"}{"\n"}
            <span className="text-slate-500 select-none">2 </span>{"2026-09-04T12:00:00Z,192.168.1.50,10.0.4.120,BLOCK,SEC_SURGE_99"}{"\n"}
            <span className="text-slate-500 select-none">3 </span>{"2026-09-04T12:00:01Z,192.168.1.52,10.0.4.121,ALLOW,SEC_SURGE_01"}
          </>
        );
      default:
        return (
          <>
            <span className="text-slate-500 select-none">1 </span>{"{}"}
          </>
        );
    }
  };

  return (
    <div className="onboarding-page-container">
      <Navbar />

      <main className="onboarding-main">
        <div className="onboarding-max-w-7xl">
          {/* Breadcrumb / Header */}
          <div style={{ marginBottom: "2rem", display: "flex", justifyContent: "space-between", flexWrap: "wrap", gap: "1rem" }}>
            <div>
              <h1 className="onboarding-header-title">
                Onboard New Log Stream
                <span className="onboarding-header-badge">V2.4 Ingest Engine</span>
              </h1>
              <p className="onboarding-header-desc">Register telemetry connectors and calibrate custom schemas.</p>
            </div>
          </div>

          <div className="onboarding-grid">
            {/* LEFT PANEL: Ingestion Form */}
            <section className="onboarding-col-7">
              <div className="onboarding-card">
                <div className="onboarding-card-header">
                  <div>
                    <h2 className="onboarding-card-title">New Ingestion Pipeline</h2>
                    <p className="onboarding-card-subtitle">Configure vendor schema definitions and stream protocols.</p>
                  </div>
                  <div className="onboarding-step-badge">01</div>
                </div>

                <div className="onboarding-mode-toggle">
                  <button
                    className={`onboarding-mode-btn ${onboardMode === "NEW" ? "active" : "inactive"}`}
                    type="button"
                    onClick={() => {
                      setOnboardMode("NEW");
                      setError(null);
                    }}
                  >
                    <svg style={{ width: "14px", height: "14px" }} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M12 4v16m8-8H4" strokeLinecap="round" strokeLinejoin="round"></path></svg>
                    <span>Create New Log Source</span>
                  </button>
                  <button
                    className={`onboarding-mode-btn ${onboardMode === "UPDATE" ? "active" : "inactive"}`}
                    type="button"
                    onClick={() => {
                      setOnboardMode("UPDATE");
                      setError(null);
                    }}
                  >
                    <svg style={{ width: "14px", height: "14px" }} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" strokeLinecap="round" strokeLinejoin="round"></path></svg>
                    <span>Update Existing Source</span>
                  </button>
                </div>

                {error && (
                  <div style={{ padding: "0.75rem", marginBottom: "1.25rem", borderRadius: "0.5rem", backgroundColor: "#fef2f2", color: "#b91c1c", fontSize: "0.875rem", border: "1px solid #f87171" }}>
                    <strong>Error: </strong>{error}
                  </div>
                )}

                <form onSubmit={handleSubmit}>
                  {onboardMode === "UPDATE" ? (
                    <div className="onboarding-form-group">
                      <div className="onboarding-label-wrapper">
                        <label className="onboarding-label" htmlFor="sourceSelect">Select Active Log Source <span style={{ color: "#ef4444" }}>*</span></label>
                        <span className="onboarding-label-tag">TARGET SOURCE</span>
                      </div>
                      <div className="onboarding-input-wrapper">
                        <select
                          id="sourceSelect"
                          className="onboarding-input no-icon"
                          required={onboardMode === "UPDATE"}
                          value={selectedSourceId}
                          onChange={(e) => {
                            const srcId = e.target.value;
                            setSelectedSourceId(srcId);
                            const found = mySources.find(s => s.sourceId === srcId);
                            if (found) {
                              setSourceName(found.sourceName || "");
                              setSourceType(found.sourceType || "");
                            }
                          }}
                          style={{ width: "100%", padding: "0.625rem 0.875rem" }}
                        >
                          <option value="">-- Select Active Source to Update --</option>
                          {mySources.map((s) => (
                            <option key={s.sourceId} value={s.sourceId}>
                              {s.sourceName} ({s.sourceId}) — {s.sourceType} [{s.status}]
                            </option>
                          ))}
                        </select>
                      </div>
                    </div>
                  ) : (
                    <>
                      {/* Vendor Name */}
                      <div className="onboarding-form-group">
                        <div className="onboarding-label-wrapper">
                          <label className="onboarding-label" htmlFor="vendorName">Vendor Name <span style={{ color: "#ef4444" }}>*</span></label>
                          <span className="onboarding-label-tag">PROVIDER SPEC</span>
                        </div>
                        <div className="onboarding-input-wrapper">
                          <div className="onboarding-input-icon">
                            <svg style={{ width: "16px", height: "16px" }} fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"></path></svg>
                          </div>
                          <input
                            id="vendorName"
                            className="onboarding-input"
                            placeholder="e.g. CrowdStrike, Palo Alto, AWS"
                            required={onboardMode === "NEW"}
                            type="text"
                            value={vendorName}
                            onChange={(e) => setVendorName(e.target.value)}
                          />
                        </div>
                      </div>

                      {/* Log Source Name */}
                      <div className="onboarding-form-group">
                        <div className="onboarding-label-wrapper">
                          <label className="onboarding-label" htmlFor="sourceName">Log Source Name <span style={{ color: "#ef4444" }}>*</span></label>
                          <span className="onboarding-label-tag">IDENTIFIER</span>
                        </div>
                        <div className="onboarding-input-wrapper">
                          <div className="onboarding-input-icon">
                            <svg style={{ width: "16px", height: "16px" }} fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"></path></svg>
                          </div>
                          <input
                            id="sourceName"
                            className="onboarding-input"
                            placeholder="e.g. Falcon EDR Logs, Okta Auth Events"
                            required={onboardMode === "NEW"}
                            type="text"
                            value={sourceName}
                            onChange={(e) => setSourceName(e.target.value)}
                          />
                        </div>
                      </div>
                    </>
                  )}

                  {/* Source Format Selector */}
                  <div className="onboarding-form-group">
                    <div className="onboarding-label-wrapper">
                      <label className="onboarding-label">Source Format <span style={{ color: "#ef4444" }}>*</span></label>
                      <span className="onboarding-label-tag">PARSING ENGINE</span>
                    </div>
                    <div className="onboarding-format-grid">
                      {["SYSLOG", "JSON", "CEF", "CSV"].map(fmt => (
                        <button
                          key={fmt}
                          type="button"
                          className={`onboarding-format-btn ${sourceType === fmt ? "active" : ""}`}
                          onClick={() => setSourceType(fmt)}
                        >
                          {fmt}
                        </button>
                      ))}
                    </div>
                  </div>

                  {/* Sensor Configuration */}
                  <div className={`onboarding-form-group onboarding-sensor-wrapper ${isSensorMode ? "active" : ""}`}>
                    <div className="onboarding-sensor-header">
                      <div style={{ flex: 1, cursor: "pointer", userSelect: "none" }} onClick={toggleSensorMode}>
                        <div className="onboarding-sensor-title">
                          <span style={{ fontSize: "0.875rem", fontWeight: 600, color: "#111827" }}>This is a sensor/telemetry source</span>
                          <span className="onboarding-header-badge" style={{ fontSize: "0.625rem", padding: "0.125rem 0.5rem" }}>IOT & METRICS</span>
                        </div>
                        <p className="onboarding-sensor-desc">Enable this for high-frequency numeric sensor data (e.g. IoT, metrics) that requires delta-based aggregation instead of per-event logging.</p>
                      </div>
                      <button
                        type="button"
                        className={`onboarding-switch ${isSensorMode ? "active" : ""}`}
                        role="switch"
                        aria-checked={isSensorMode}
                        onClick={toggleSensorMode}
                      >
                        <span className="onboarding-switch-thumb"></span>
                      </button>
                    </div>

                    {isSensorMode && (
                      <div className="onboarding-sensor-body">
                        <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", marginBottom: "0.75rem" }}>
                          <span style={{ width: "1.25rem", height: "1.25rem", borderRadius: "9999px", backgroundColor: "#f0fdfa", color: "var(--ulpf-teal)", display: "flex", alignItems: "center", justifyContent: "center", fontSize: "0.75rem", fontWeight: "bold", border: "1px solid rgba(204, 251, 241, 0.6)" }}>i</span>
                          <h4 style={{ fontSize: "0.75rem", fontWeight: "bold", color: "#1f2937", textTransform: "uppercase", letterSpacing: "0.025em", margin: 0 }}>Sensor Configuration</h4>
                        </div>
                        <div className="onboarding-sensor-grid">
                          <div>
                            <label className="onboarding-label" style={{ display: "block", marginBottom: "0.375rem" }} htmlFor="sensorDeltaThreshold">DELTA THRESHOLD</label>
                            <input
                              className="onboarding-input no-icon"
                              id="sensorDeltaThreshold"
                              placeholder="e.g. 2.5"
                              step="any"
                              type="number"
                              value={delta}
                              onChange={(e) => setDelta(e.target.value)}
                            />
                            <p style={{ fontSize: "0.6875rem", color: "#9ca3af", marginTop: "0.25rem", lineHeight: "1.5" }}>Minimum value change required before a new event is emitted.</p>
                          </div>
                          <div>
                            <label className="onboarding-label" style={{ display: "block", marginBottom: "0.375rem" }} htmlFor="sensorMaxInterval">MAX INTERVAL (MS)</label>
                            <input
                              className="onboarding-input no-icon"
                              id="sensorMaxInterval"
                              placeholder="e.g. 60000"
                              step="100"
                              type="number"
                              value={maxIntervalMs}
                              onChange={(e) => setMaxIntervalMs(e.target.value)}
                            />
                            <p style={{ fontSize: "0.6875rem", color: "#9ca3af", marginTop: "0.25rem", lineHeight: "1.5" }}>Maximum time before an emission is forced. Defaults to 60000ms.</p>
                          </div>
                          <div>
                            <label className="onboarding-label" style={{ display: "block", marginBottom: "0.375rem" }} htmlFor="sensorFieldName">SENSOR FIELD</label>
                            <input
                              className="onboarding-input no-icon"
                              id="sensorFieldName"
                              placeholder="e.g. temp_celsius"
                              type="text"
                              value={sensorField}
                              onChange={(e) => setSensorField(e.target.value)}
                            />
                            <p style={{ fontSize: "0.6875rem", color: "#9ca3af", marginTop: "0.25rem", lineHeight: "1.5" }}>The JSON key containing the numeric sensor value. Leave blank to auto-detect.</p>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* Sample Log File */}
                  <div className="onboarding-form-group">
                    <div className="onboarding-label-wrapper">
                      <span className="onboarding-label">Sample Log File</span>
                      <span className="onboarding-header-badge" style={{ fontSize: "0.6875rem", borderRadius: "9999px" }}>Auto-Discovery</span>
                    </div>

                    {sampleLogFile ? (
                      <div className="onboarding-file-attached">
                        <div style={{ display: "flex", alignItems: "center", gap: "0.75rem" }}>
                          <div style={{ width: "2.25rem", height: "2.25rem", borderRadius: "0.75rem", backgroundColor: "white", border: "1px solid #ccfbf1", display: "flex", alignItems: "center", justifyContent: "center" }}>
                            <svg style={{ width: "1.25rem", height: "1.25rem", color: "var(--ulpf-teal)" }} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" x2="8" y1="13" y2="13"></line><line x1="16" x2="8" y1="17" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
                          </div>
                          <div>
                            <p style={{ fontSize: "0.75rem", fontWeight: "bold", color: "#1f2937", margin: 0 }}>{sampleLogFile.name}</p>
                            <p style={{ fontSize: "0.6875rem", fontFamily: "var(--font-mono)", color: "#6b7280", margin: 0 }}>{Math.round(sampleLogFile.size / 1024)} KB</p>
                          </div>
                        </div>
                        <button type="button" onClick={() => setSampleLogFile(null)} style={{ width: "1.5rem", height: "1.5rem", borderRadius: "9999px", display: "flex", alignItems: "center", justifyContent: "center", color: "#9ca3af", background: "transparent", border: "none", cursor: "pointer" }} title="Remove file">
                          <svg style={{ width: "1rem", height: "1rem" }} fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M6 18L18 6M6 6l12 12" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"></path></svg>
                        </button>
                      </div>
                    ) : (
                      <div className="onboarding-dropzone" onClick={() => document.getElementById('sampleLogFileInput').click()}>
                        <div className="onboarding-dropzone-icon">
                          <svg style={{ width: "1.25rem", height: "1.25rem" }} fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"></path></svg>
                        </div>
                        <p style={{ fontSize: "0.75rem", fontWeight: "bold", color: "#374151", margin: "0 0 0.125rem 0" }}>Click to select sample log file</p>
                        <p style={{ fontSize: "0.6875rem", color: "#9ca3af", margin: 0 }}>Accepted: .log, .csv, .json, .txt</p>
                        <input
                          id="sampleLogFileInput"
                          type="file"
                          accept={ACCEPTED_FILE_TYPES}
                          onChange={(e) => setSampleLogFile(e.target.files[0] || null)}
                          style={{ display: "none" }}
                        />
                      </div>
                    )}
                  </div>

                  {/* Schema File */}
                  <div className="onboarding-form-group">
                    <div className="onboarding-label-wrapper">
                      <label className="onboarding-label" style={{ color: "#6b7280" }}>Schema Specification (Optional)</label>
                      <span className="onboarding-label-tag">AVRO / JSON-SCHEMA</span>
                    </div>
                    <div style={{ border: "1px dashed #e5e7eb", borderRadius: "0.75rem", padding: "0.75rem 1rem", display: "flex", alignItems: "center", justifyContent: "space-between", backgroundColor: "rgba(249, 250, 251, 0.4)" }}>
                      <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", fontSize: "0.75rem", color: "#6b7280" }}>
                        <svg style={{ width: "1rem", height: "1rem", color: "#9ca3af" }} fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"></path></svg>
                        <span>{schemaFile ? schemaFile.name : "Upload schema dictionary (Optional)"}</span>
                      </div>
                      <button type="button" onClick={() => document.getElementById('schemaFileInput').click()} style={{ fontSize: "0.75rem", fontWeight: 600, color: "var(--ulpf-teal-dark)", background: "transparent", border: "none", cursor: "pointer", padding: 0 }}>
                        {schemaFile ? "Replace" : "Select"}
                      </button>
                      <input
                        id="schemaFileInput"
                        type="file"
                        accept={ACCEPTED_FILE_TYPES}
                        onChange={(e) => setSchemaFile(e.target.files[0] || null)}
                        style={{ display: "none" }}
                      />
                    </div>
                  </div>

                  <div style={{ paddingTop: "0.5rem" }}>
                    <button
                      className="onboarding-submit-btn"
                      type="submit"
                      disabled={loading || !sourceType}
                    >
                      {loading ? (
                        <>
                          <svg className="animate-spin" style={{ width: "1rem", height: "1rem", animation: "spin 1s linear infinite" }} xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                            <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" opacity="0.25"></circle>
                            <path fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" opacity="0.75"></path>
                          </svg>
                          Submitting...
                        </>
                      ) : (
                        "Submit Onboarding Request"
                      )}
                    </button>
                  </div>

                </form>
              </div>
            </section>

            {/* RIGHT PANEL: Guidelines & Live Preview */}
            <section className="onboarding-col-5">
              <div className="onboarding-card" style={{ marginBottom: "1.5rem" }}>
                <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", paddingBottom: "0.75rem", marginBottom: "0.75rem", borderBottom: "1px solid #f3f4f6" }}>
                  <span style={{ width: "1.5rem", height: "1.5rem", borderRadius: "9999px", backgroundColor: "#fffbeb", color: "#d97706", display: "flex", alignItems: "center", justifyContent: "center", fontSize: "0.75rem", fontWeight: "bold", border: "1px solid rgba(253, 230, 138, 0.6)" }}>i</span>
                  <h3 style={{ fontSize: "0.875rem", fontWeight: "bold", color: "#1f2937", textTransform: "uppercase", letterSpacing: "0.025em", margin: 0 }}>Ingestion Guidelines</h3>
                </div>
                <ul className="onboarding-guidelines-list">
                  <li>
                    <div className="onboarding-guidelines-icon"><svg style={{ width: "0.625rem", height: "0.625rem" }} fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M5 13l4 4L19 7" strokeLinecap="round" strokeLinejoin="round" strokeWidth="3"></path></svg></div>
                    <span><strong>Supported Formats:</strong> .log, .json, .csv, .txt up to 10MB sample payload size.</span>
                  </li>
                  <li>
                    <div className="onboarding-guidelines-icon"><svg style={{ width: "0.625rem", height: "0.625rem" }} fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M5 13l4 4L19 7" strokeLinecap="round" strokeLinejoin="round" strokeWidth="3"></path></svg></div>
                    <span><strong>Manual Verification:</strong> Submitted requests will be reviewed by an administrator prior to activation.</span>
                  </li>
                  <li>
                    <div className="onboarding-guidelines-icon"><svg style={{ width: "0.625rem", height: "0.625rem" }} fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M5 13l4 4L19 7" strokeLinecap="round" strokeLinejoin="round" strokeWidth="3"></path></svg></div>
                    <span><strong>Security & Hygiene:</strong> PII anonymization heuristics run within in-memory buffer before indexing.</span>
                  </li>
                </ul>
              </div>

              <div className="onboarding-card">
                <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", paddingBottom: "0.75rem", marginBottom: "0.75rem", borderBottom: "1px solid #f3f4f6" }}>
                  <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                    <span style={{ fontSize: "0.875rem" }}>📄</span>
                    <h3 style={{ fontSize: "0.875rem", fontWeight: "bold", color: "#1f2937", margin: 0 }}>Format Reference Preview</h3>
                  </div>
                  <span style={{ fontSize: "0.625rem", fontFamily: "var(--font-mono)", fontWeight: 600, padding: "0.125rem 0.5rem", backgroundColor: "#f3f4f6", color: "#4b5563", borderRadius: "0.375rem", border: "1px solid #e5e7eb" }}>
                    SYNTAX: <span>{sourceType || "NONE"}</span>
                  </span>
                </div>

                <div className="onboarding-code-viewer">
                  <div style={{ position: "absolute", top: "0.5rem", right: "0.5rem", fontSize: "0.5625rem", color: "#64748b" }}>UTF-8</div>
                  <pre>
                    {renderFormatSnippet(sourceType)}
                  </pre>
                </div>

                <div style={{ marginTop: "1rem", paddingTop: "0.75rem", borderTop: "1px solid #f3f4f6", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                  <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                    <div style={{ width: "1.75rem", height: "1.75rem", borderRadius: "0.75rem", backgroundColor: "#f0fdfa", border: "1px solid #ccfbf1", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", position: "relative", boxShadow: "0 1px 2px 0 rgba(0, 0, 0, 0.05)" }}>
                      <div style={{ display: "flex", gap: "0.25rem", marginBottom: "0.125rem" }}>
                        <span style={{ width: "0.25rem", height: "0.25rem", borderRadius: "9999px", backgroundColor: "var(--ulpf-teal-dark)" }}></span>
                        <span style={{ width: "0.25rem", height: "0.25rem", borderRadius: "9999px", backgroundColor: "var(--ulpf-teal-dark)" }}></span>
                      </div>
                      <div style={{ width: "0.5rem", height: "0.125rem", backgroundColor: "var(--ulpf-teal-dark)", borderRadius: "9999px" }}></div>
                    </div>
                    <div style={{ fontSize: "0.6875rem", lineHeight: 1.25 }}>
                      <span style={{ fontWeight: "bold", color: "#1f2937" }}>Schema Learning Engine</span>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          </div>

          {/* SECTION: Pipelines & Requests History */}
          <div className="onboarding-card onboarding-history-section">
            <div style={{ display: "flex", flexDirection: "column", gap: "1rem", paddingBottom: "1.5rem", borderBottom: "1px solid #f3f4f6" }}>
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: "1rem" }}>
                <div style={{ display: "flex", alignItems: "center", gap: "0.75rem" }}>
                  <div style={{ width: "2.25rem", height: "2.25rem", borderRadius: "1rem", backgroundColor: "#f0fdfa", color: "var(--ulpf-teal)", display: "flex", alignItems: "center", justifyContent: "center", border: "1px solid #ccfbf1" }}>
                    <svg style={{ width: "1rem", height: "1rem" }} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" strokeLinecap="round" strokeLinejoin="round"></path></svg>
                  </div>
                  <div>
                    <h2 style={{ fontSize: "1.125rem", fontWeight: "bold", color: "#111827", margin: 0 }}>Pipelines & Requests History</h2>
                    <p style={{ fontSize: "0.75rem", color: "#6b7280", margin: 0 }}>Audit submitted schema onboarding tickets and inspect active stream endpoints.</p>
                  </div>
                </div>
                <div className="onboarding-tabs">
                  <button
                    className={`onboarding-mode-btn ${historyTab === "requests" ? "active" : "inactive"}`}
                    onClick={() => setHistoryTab("requests")}
                    type="button"
                  >
                    My Onboarding Requests
                  </button>
                  <button
                    className={`onboarding-mode-btn ${historyTab === "active" ? "active" : "inactive"}`}
                    onClick={() => setHistoryTab("active")}
                    type="button"
                  >
                    My Active Log Sources
                  </button>
                </div>
              </div>
            </div>

            <div style={{ marginTop: "1.5rem", overflowX: "auto" }}>
              {myRequestsLoading ? (
                <p style={{ fontSize: "0.875rem", color: "#6b7280" }}>Loading data...</p>
              ) : historyTab === "requests" ? (
                myRequests.length === 0 ? (
                  <p style={{ fontSize: "0.875rem", color: "#6b7280" }}>No onboarding requests submitted yet.</p>
                ) : (
                  <table className="onboarding-table">
                    <thead>
                      <tr>
                        <th>Request ID</th>
                        <th>Type</th>
                        <th>Status</th>
                        <th>Date</th>
                      </tr>
                    </thead>
                    <tbody>
                      {myRequests.map((r) => (
                        <tr key={r.requestId}>
                          <td style={{ fontFamily: "var(--font-mono)", fontWeight: 600, color: "#111827" }}>{r.requestId}</td>
                          <td style={{ fontWeight: 500, color: "#1f2937" }}>{r.requestType}</td>
                          <td>
                            <span className={`status-badge ${r.status === "APPROVED" ? "status-approved" : r.status === "REJECTED" ? "status-rejected" : "status-submitted"}`}>
                              {r.status}
                            </span>
                          </td>
                          <td style={{ fontFamily: "var(--font-mono)", color: "#6b7280" }}>{r.createdAt}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )
              ) : (
                mySources.length === 0 ? (
                  <p style={{ fontSize: "0.875rem", color: "#6b7280" }}>No active sources onboarded yet.</p>
                ) : (
                  <table className="onboarding-table">
                    <thead>
                      <tr>
                        <th>SOURCE NAME</th>
                        <th>SOURCE ID</th>
                        <th>TYPE</th>
                        <th>STATUS</th>
                        <th>DATE</th>
                      </tr>
                    </thead>
                    <tbody>
                      {mySources.map((s) => (
                        <tr key={s.sourceId}>
                          <td style={{ fontWeight: 500, color: "#1f2937", display: "flex", alignItems: "center", gap: "0.5rem" }}>
                            <span style={{ width: "0.5rem", height: "0.5rem", borderRadius: "9999px", backgroundColor: "var(--ulpf-teal)" }}></span>
                            {s.sourceName}
                          </td>
                          <td style={{ fontFamily: "var(--font-mono)", color: "#6b7280" }}>{s.sourceId}</td>
                          <td>
                            <span style={{ display: "inline-flex", alignItems: "center", padding: "0.125rem 0.5rem", borderRadius: "0.375rem", fontSize: "0.6875rem", fontFamily: "var(--font-mono)", fontWeight: 600, backgroundColor: "#f0fdfa", color: "var(--ulpf-teal-dark)", border: "1px solid #ccfbf1" }}>
                              {s.sourceType}
                            </span>
                          </td>
                          <td>
                            <span className={`status-badge ${s.status === "ACTIVE" ? "status-approved" : s.status === "SUSPENDED" ? "status-submitted" : "status-rejected"}`}>
                              {s.status}
                            </span>
                          </td>
                          <td style={{ fontFamily: "var(--font-mono)", color: "#6b7280" }}>{s.createdAt}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )
              )}
            </div>
          </div>
        </div>
      </main>

      {/* Success Modal */}
      {showModal && (
        <div className="onboarding-modal-overlay" onClick={() => setShowModal(false)}>
          <div className="onboarding-modal-card" onClick={(e) => e.stopPropagation()}>
            <button
              onClick={() => setShowModal(false)}
              style={{ position: "absolute", top: "1rem", right: "1rem", color: "#9ca3af", padding: "0.375rem", borderRadius: "9999px", background: "transparent", border: "none", cursor: "pointer" }}
              title="Close modal"
            >
              <svg style={{ width: "1.25rem", height: "1.25rem" }} fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M6 18L18 6M6 6l12 12" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"></path></svg>
            </button>

            <div style={{ margin: "0 auto 1.25rem auto", width: "4rem", height: "4rem", borderRadius: "1.5rem", backgroundColor: "#f0fdfa", border: "1px solid rgba(204, 251, 241, 0.8)", color: "var(--ulpf-teal)", display: "flex", alignItems: "center", justifyContent: "center", position: "relative", boxShadow: "var(--box-shadow-soft)" }}>
              <span style={{ fontSize: "1.5rem", userSelect: "none" }}>✨</span>
              <div style={{ position: "absolute", top: "-0.25rem", right: "-0.25rem", width: "1.5rem", height: "1.5rem", backgroundColor: "#10b981", borderRadius: "9999px", color: "white", display: "flex", alignItems: "center", justifyContent: "center", fontSize: "0.75rem", fontWeight: "bold", boxShadow: "0 1px 2px 0 rgba(0, 0, 0, 0.05)" }}>
                <svg style={{ width: "0.875rem", height: "0.875rem" }} fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M5 13l4 4L19 7" strokeLinecap="round" strokeLinejoin="round"></path></svg>
              </div>
            </div>

            <h3 style={{ fontSize: "1.5rem", fontWeight: "bold", color: "#111827", marginBottom: "0.5rem" }}>Request Submitted!</h3>
            <p style={{ fontSize: "0.875rem", color: "#4b5563", marginBottom: "1.5rem", lineHeight: "1.625" }}>
              Your onboarding request (<span style={{ fontFamily: "var(--font-mono)", fontWeight: 600, color: "#111827", backgroundColor: "#f3f4f6", padding: "0.125rem 0.375rem", borderRadius: "0.25rem", border: "1px solid rgba(229, 231, 235, 0.6)" }}>{result?.requestId}</span>) is now under review. Our system will evaluate your sample and prepare standard indices.
            </p>

            {result?.apiKey ? (
              <div style={{ backgroundColor: "#fffbeb", border: "1px solid rgba(253, 230, 138, 0.8)", borderRadius: "1rem", padding: "1rem", marginBottom: "1.25rem", textAlign: "left" }}>
                <div style={{ display: "flex", alignItems: "flex-start", gap: "0.625rem", marginBottom: "0.625rem" }}>
                  <span style={{ color: "#f59e0b", fontSize: "1rem", lineHeight: 1 }}>⚠️</span>
                  <div>
                    <p style={{ fontSize: "0.75rem", fontWeight: "bold", color: "#78350f", letterSpacing: "-0.025em", margin: 0 }}>Save this API key now!</p>
                    <p style={{ fontSize: "0.6875rem", color: "#b45309", marginTop: "0.125rem", margin: 0 }}>It will not be shown again for security reasons.</p>
                  </div>
                </div>
                <div style={{ backgroundColor: "white", borderRadius: "0.75rem", border: "1px solid rgba(253, 230, 138, 0.7)", padding: "0.625rem", display: "flex", alignItems: "center", justifyContent: "space-between", gap: "0.5rem" }}>
                  <span style={{ fontFamily: "var(--font-mono)", fontSize: "0.75rem", fontWeight: 600, color: "#1f2937", wordBreak: "break-all", userSelect: "all" }}>
                    {result.apiKey}
                  </span>
                  <button onClick={copyModalApiKey} type="button" style={{ flexShrink: 0, display: "inline-flex", alignItems: "center", gap: "0.25rem", fontSize: "0.6875rem", fontWeight: 600, color: "var(--ulpf-teal-dark)", border: "1px solid #ccfbf1", backgroundColor: "rgba(240, 253, 250, 0.7)", padding: "0.25rem 0.625rem", borderRadius: "0.5rem", transition: "all 0.2s", cursor: "pointer" }}>
                    {copySuccess ? "✅ Copied!" : "📋 Copy"}
                  </button>
                </div>
              </div>
            ) : (
              <div style={{ backgroundColor: "#f0fdfa", border: "1px solid #ccfbf1", borderRadius: "1rem", padding: "1rem", marginBottom: "1.25rem", textAlign: "left" }}>
                <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", marginBottom: "0.25rem" }}>
                  <span style={{ fontSize: "1rem" }}>🔑</span>
                  <p style={{ fontSize: "0.75rem", fontWeight: "bold", color: "#0f766e", margin: 0 }}>Active API Key Preserved</p>
                </div>
                <p style={{ fontSize: "0.6875rem", color: "#0d9488", margin: 0, lineHeight: 1.4 }}>
                  Your existing API key remains active and will apply to the proposed schema version once approved by an administrator.
                </p>
              </div>
            )}

            <div style={{ display: "flex", flexDirection: "column", gap: "0.75rem", marginTop: "1.5rem" }}>
              <button
                onClick={() => {
                  setShowModal(false);
                  navigate("/notifications");
                }}
                className="onboarding-submit-btn"
                style={{ fontSize: "0.875rem", padding: "0.75rem 1.5rem", borderRadius: "9999px" }}
              >
                View Notifications
              </button>
              <button
                onClick={() => {
                  setShowModal(false);
                  setHistoryTab("requests");
                  window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
                }}
                style={{ width: "100%", fontSize: "0.75rem", fontWeight: 600, color: "#6b7280", padding: "0.25rem 0", background: "transparent", border: "none", cursor: "pointer" }}
              >
                Start another request
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default OnboardingPage;
