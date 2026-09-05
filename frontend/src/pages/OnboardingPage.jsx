import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import client from "../api/client";
import Navbar from "../components/Navbar";

function OnboardingPage() {
  const navigate = useNavigate();

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
    formData.append("vendorName", vendorName);
    formData.append("sourceName", sourceName);
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
      const response = await client.post(
        `/v1/onboard/${username}`,
        formData
      );

      setResult({
        requestId: response.data.requestId,
        sourceId: response.data.sourceId,
        vendorId: response.data.vendorId,
        apiKey: response.data.apiKey,
        status: response.data.status,
        message: response.data.message,
      });

      // Clear form & refetch user data
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

  if (!username) {
    return null;
  }

  const ACCEPTED_FILE_TYPES = ".log,.csv,.json,.txt";

  return (
    <div>
      <Navbar />

      <div style={{ padding: "0 20px" }}>
        <h2>Log Source Onboarding & Vendor Portal</h2>

        <form onSubmit={handleSubmit} style={{ background: "white", padding: "20px", borderRadius: "8px", boxShadow: "0 2px 6px rgba(0,0,0,0.08)", marginBottom: "25px" }}>
          <h3>Submit New Log Source Request</h3>
          <div style={{ marginBottom: "12px" }}>
            <label htmlFor="onboard-vendor-name" style={{ fontWeight: "600", display: "block", marginBottom: "4px" }}>Vendor Name (required)</label>
            <input
              id="onboard-vendor-name"
              type="text"
              value={vendorName}
              onChange={(e) => setVendorName(e.target.value)}
              placeholder="e.g. CrowdStrike"
              required
              style={{ width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid #ccc" }}
            />
          </div>

          <div style={{ marginBottom: "12px" }}>
            <label htmlFor="onboard-source-name" style={{ fontWeight: "600", display: "block", marginBottom: "4px" }}>Source Name (required)</label>
            <input
              id="onboard-source-name"
              type="text"
              value={sourceName}
              onChange={(e) => setSourceName(e.target.value)}
              placeholder="e.g. Falcon EDR Logs"
              required
              style={{ width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid #ccc" }}
            />
          </div>

          <div style={{ marginBottom: "12px" }}>
            <label htmlFor="onboard-source-type" style={{ fontWeight: "600", display: "block", marginBottom: "4px" }}>Source Format (required)</label>
            <input
              id="onboard-source-type"
              type="text"
              value={sourceType}
              onChange={(e) => setSourceType(e.target.value)}
              placeholder="e.g. SYSLOG, JSON, CEF"
              required
              style={{ width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid #ccc" }}
            />
          </div>

          {/* Log Stream Type Selector & Sensor Optimization Settings */}
          <div style={{ marginBottom: "16px", background: "#f8f9fa", padding: "14px", borderRadius: "6px", border: "1px solid #e9ecef" }}>
            <label style={{ fontWeight: "600", display: "block", marginBottom: "6px" }}>Log Stream Type</label>
            <div style={{ display: "flex", gap: "15px", marginBottom: "10px" }}>
              <label style={{ cursor: "pointer" }}>
                <input
                  type="radio"
                  name="logType"
                  value="REG_LOG"
                  checked={logType === "REG_LOG"}
                  onChange={(e) => setLogType(e.target.value)}
                  style={{ marginRight: "6px" }}
                />
                Regular Log Stream (REG_LOG)
              </label>
              <label style={{ cursor: "pointer" }}>
                <input
                  type="radio"
                  name="logType"
                  value="SEN_TEL"
                  checked={logType === "SEN_TEL"}
                  onChange={(e) => setLogType(e.target.value)}
                  style={{ marginRight: "6px" }}
                />
                ⚡ Sensor / Telemetry Stream (SEN_TEL)
              </label>
            </div>

            {logType === "SEN_TEL" && (
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: "12px", marginTop: "10px", background: "#eef2f7", padding: "12px", borderRadius: "6px" }}>
                <div>
                  <label htmlFor="sensor-delta" style={{ fontSize: "0.85em", fontWeight: "600", display: "block", marginBottom: "4px" }}>Emission Delta Threshold</label>
                  <input
                    id="sensor-delta"
                    type="number"
                    step="any"
                    value={delta}
                    onChange={(e) => setDelta(e.target.value)}
                    placeholder="e.g. 0.5"
                    style={{ width: "100%", padding: "6px", borderRadius: "4px", border: "1px solid #ccc", fontSize: "0.9em" }}
                  />
                </div>
                <div>
                  <label htmlFor="sensor-max-interval" style={{ fontSize: "0.85em", fontWeight: "600", display: "block", marginBottom: "4px" }}>Max Interval (ms)</label>
                  <input
                    id="sensor-max-interval"
                    type="number"
                    value={maxIntervalMs}
                    onChange={(e) => setMaxIntervalMs(e.target.value)}
                    placeholder="60000"
                    style={{ width: "100%", padding: "6px", borderRadius: "4px", border: "1px solid #ccc", fontSize: "0.9em" }}
                  />
                </div>
                <div>
                  <label htmlFor="sensor-field" style={{ fontSize: "0.85em", fontWeight: "600", display: "block", marginBottom: "4px" }}>Sensor Field Name</label>
                  <input
                    id="sensor-field"
                    type="text"
                    value={sensorField}
                    onChange={(e) => setSensorField(e.target.value)}
                    placeholder="e.g. temperature"
                    style={{ width: "100%", padding: "6px", borderRadius: "4px", border: "1px solid #ccc", fontSize: "0.9em" }}
                  />
                </div>
              </div>
            )}
          </div>

          <div style={{ marginBottom: "12px" }}>
            <label htmlFor="onboard-sample-log" style={{ fontWeight: "600", display: "block", marginBottom: "4px" }}>Sample Log File (optional)</label>
            <input
              id="onboard-sample-log"
              type="file"
              accept={ACCEPTED_FILE_TYPES}
              onChange={(e) => setSampleLogFile(e.target.files[0] || null)}
            />
          </div>

          <div style={{ marginBottom: "12px" }}>
            <label htmlFor="onboard-schema" style={{ fontWeight: "600", display: "block", marginBottom: "4px" }}>Schema File (optional)</label>
            <input
              id="onboard-schema"
              type="file"
              accept={ACCEPTED_FILE_TYPES}
              onChange={(e) => setSchemaFile(e.target.files[0] || null)}
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            style={{ background: "#006b5f", color: "white", border: "none", padding: "10px 20px", borderRadius: "4px", fontWeight: "600", cursor: "pointer" }}
          >
            {loading ? "Submitting…" : "🚀 Submit Onboarding Request"}
          </button>
        </form>

        {result && (
          <div style={{ background: "#e8f5e9", border: "1px solid #4CAF50", padding: "15px", borderRadius: "6px", marginBottom: "25px" }}>
            <h3 style={{ margin: "0 0 10px 0", color: "#2e7d32" }}>Request Submitted Successfully</h3>
            <p><strong>Request ID:</strong> <code style={{ fontFamily: "monospace" }}>{result.requestId}</code></p>
            <p><strong>Source ID:</strong> <code style={{ fontFamily: "monospace" }}>{result.sourceId}</code></p>
            <p><strong>Status:</strong> {result.status}</p>

            {result.apiKey && (
              <div style={{ background: "#fff3cd", border: "1px solid #ffebaba", padding: "12px", borderRadius: "6px", margin: "10px 0" }}>
                <p style={{ color: "#856404", fontWeight: "bold", margin: "0 0 5px 0" }}>
                  🔑 Save your Raw API Key now! For security reasons, it will not be displayed again:
                </p>
                <code style={{ background: "#e0e0e0", padding: "6px 12px", borderRadius: "4px", fontSize: "1.1em", fontFamily: "monospace" }}>
                  {result.apiKey}
                </code>
              </div>
            )}

            {result.message && <p>{result.message}</p>}
          </div>
        )}

        {error && (
          <div style={{ background: "#f8d7da", color: "#721c24", padding: "12px", borderRadius: "6px", marginBottom: "25px" }}>
            {error}
          </div>
        )}

        {/* My Onboarding Requests Feed Feed */}
        <div style={{ background: "white", padding: "20px", borderRadius: "8px", boxShadow: "0 2px 6px rgba(0,0,0,0.08)", marginBottom: "25px" }}>
          <h3>My Onboarding Requests</h3>
          {myRequestsLoading && <p>Loading requests…</p>}
          {!myRequestsLoading && myRequests.length === 0 && <p style={{ color: "#666" }}>No onboarding requests submitted yet.</p>}
          {!myRequestsLoading && myRequests.length > 0 && (
            <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "0.9em" }}>
              <thead>
                <tr style={{ background: "#f0f4f8", textAlign: "left" }}>
                  <th style={{ padding: "8px" }}>Request ID</th>
                  <th style={{ padding: "8px" }}>Type</th>
                  <th style={{ padding: "8px" }}>Status</th>
                  <th style={{ padding: "8px" }}>Submitted At</th>
                </tr>
              </thead>
              <tbody>
                {myRequests.map((r) => (
                  <tr key={r.requestId} style={{ borderBottom: "1px solid #eee" }}>
                    <td style={{ padding: "8px", fontFamily: "monospace" }}>{r.requestId}</td>
                    <td style={{ padding: "8px" }}>{r.requestType}</td>
                    <td style={{ padding: "8px" }}>
                      <span
                        style={{
                          padding: "2px 8px",
                          borderRadius: "10px",
                          fontSize: "0.8em",
                          fontWeight: "bold",
                          color: "white",
                          background: r.status === "APPROVED" ? "#28a745" : r.status === "REJECTED" ? "#dc3545" : "#fd7e14"
                        }}
                      >
                        {r.status}
                      </span>
                    </td>
                    <td style={{ padding: "8px" }}>{r.createdAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {/* My Active Sources */}
        <div style={{ background: "white", padding: "20px", borderRadius: "8px", boxShadow: "0 2px 6px rgba(0,0,0,0.08)", marginBottom: "25px" }}>
          <h3>My Log Sources</h3>
          {myRequestsLoading && <p>Loading sources…</p>}
          {!myRequestsLoading && mySources.length === 0 && <p style={{ color: "#666" }}>No sources onboarded yet.</p>}
          {!myRequestsLoading && mySources.length > 0 && (
            <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "0.9em" }}>
              <thead>
                <tr style={{ background: "#f0f4f8", textAlign: "left" }}>
                  <th style={{ padding: "8px" }}>Source Name</th>
                  <th style={{ padding: "8px" }}>Source ID</th>
                  <th style={{ padding: "8px" }}>Type</th>
                  <th style={{ padding: "8px" }}>Status</th>
                  <th style={{ padding: "8px" }}>Created At</th>
                </tr>
              </thead>
              <tbody>
                {mySources.map((s) => (
                  <tr key={s.sourceId} style={{ borderBottom: "1px solid #eee" }}>
                    <td style={{ padding: "8px", fontWeight: "600" }}>{s.sourceName}</td>
                    <td style={{ padding: "8px", fontFamily: "monospace" }}>{s.sourceId}</td>
                    <td style={{ padding: "8px" }}>{s.sourceType}</td>
                    <td style={{ padding: "8px" }}>
                      <span
                        style={{
                          padding: "2px 8px",
                          borderRadius: "10px",
                          fontSize: "0.8em",
                          fontWeight: "bold",
                          color: "white",
                          background: s.status === "ACTIVE" ? "#28a745" : s.status === "SUSPENDED" ? "#ffc107" : "#dc3545"
                        }}
                      >
                        {s.status}
                      </span>
                    </td>
                    <td style={{ padding: "8px" }}>{s.createdAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}

export default OnboardingPage;
