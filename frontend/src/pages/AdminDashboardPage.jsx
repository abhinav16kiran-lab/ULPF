import { useState, useEffect, useCallback } from "react";
import { Link } from "react-router-dom";
import client from "../api/client";
import Navbar from "../components/Navbar";

function AdminDashboardPage() {
  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [actionLoading, setActionLoading] = useState(null);
  const [actionError, setActionError] = useState(null);
  const [actionSuccess, setActionSuccess] = useState(null);

  // Editing state for candidate mappings
  const [editingRequestId, setEditingRequestId] = useState(null);
  const [editingMappingJson, setEditingMappingJson] = useState("");
  const [saveMappingLoading, setSaveMappingLoading] = useState(false);

  const fetchRequests = useCallback(async () => {
    setLoading(true);
    setError(null);

    try {
      const response = await client.get("/v1/admin/onboard");
      setRequests(response.data.requests || []);
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
  }, []);

  useEffect(() => {
    fetchRequests();
  }, [fetchRequests]);

  async function handleDecision(requestId, decision) {
    setActionLoading(requestId);
    setActionError(null);
    setActionSuccess(null);

    try {
      await client.put(`/v1/admin/onboard/${requestId}`, { decision });
      setActionSuccess(`Request #${requestId} has been ${decision}!`);
      await fetchRequests();
    } catch (err) {
      if (err.response && err.response.data) {
        if (typeof err.response.data === "string") {
          setActionError(err.response.data);
        } else if (err.response.data.error) {
          setActionError(err.response.data.error);
        } else {
          setActionError(JSON.stringify(err.response.data));
        }
      } else {
        setActionError(err.message);
      }
    } finally {
      setActionLoading(null);
    }
  }

  function startEditingMapping(req) {
    setEditingRequestId(req.requestId);
    let jsonStr = req.sampleMetadata || "{}";
    try {
      const parsed = typeof jsonStr === "string" ? JSON.parse(jsonStr) : jsonStr;
      if (parsed.candidate_mapping) {
        jsonStr = JSON.stringify(parsed.candidate_mapping, null, 2);
      } else {
        jsonStr = JSON.stringify(parsed, null, 2);
      }
    } catch {
      // keep raw string if parse fails
    }
    setEditingMappingJson(jsonStr);
  }

  async function handleSaveCandidateMapping(requestId) {
    setSaveMappingLoading(true);
    setActionError(null);
    setActionSuccess(null);

    try {
      let parsedJson;
      try {
        parsedJson = JSON.parse(editingMappingJson);
      } catch {
        throw new Error("Invalid JSON format. Please check your mapping syntax.");
      }

      await client.patch(`/v1/admin/onboard/${requestId}/mapping`, {
        mappingJson: parsedJson,
      });

      setActionSuccess(`Candidate mapping for Request #${requestId} updated successfully!`);
      setEditingRequestId(null);
      await fetchRequests();
    } catch (err) {
      if (err.response && err.response.data && err.response.data.error) {
        setActionError(err.response.data.error);
      } else {
        setActionError(err.message);
      }
    } finally {
      setSaveMappingLoading(false);
    }
  }

  return (
    <div>
      <Navbar />

      <div style={{ padding: "0 20px" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "15px" }}>
          <h2>Admin Control Panel & Schema Inspector</h2>
          <Link
            to="/analytics"
            style={{
              background: "#006b5f",
              color: "white",
              padding: "8px 16px",
              borderRadius: "4px",
              textDecoration: "none",
              fontWeight: "600",
              fontSize: "0.9em"
            }}
          >
            ⚡ Open Analytics Console
          </Link>
        </div>

        {actionSuccess && (
          <div style={{ background: "#d4edda", color: "#155724", padding: "10px 15px", borderRadius: "4px", marginBottom: "15px" }}>
            {actionSuccess}
          </div>
        )}

        {actionError && (
          <div style={{ background: "#f8d7da", color: "#721c24", padding: "10px 15px", borderRadius: "4px", marginBottom: "15px" }}>
            {actionError}
          </div>
        )}

        {loading && <p>Loading onboarding requests…</p>}
        {error && <p style={{ color: "red" }}>{error}</p>}

        {!loading && !error && requests.length === 0 && (
          <div style={{ background: "#f8f9fa", padding: "20px", borderRadius: "6px", textAlign: "center" }}>
            <p style={{ color: "#666" }}>No onboarding requests found in the system.</p>
          </div>
        )}

        {!loading && !error && requests.length > 0 && (
          <div style={{ display: "grid", gap: "20px" }}>
            {requests.map((req) => (
              <div
                key={req.requestId}
                style={{
                  background: "white",
                  padding: "16px",
                  borderRadius: "8px",
                  boxShadow: "0 2px 6px rgba(0,0,0,0.08)",
                  borderLeft: `5px solid ${
                    req.status === "APPROVED" ? "#28a745" : req.status === "REJECTED" ? "#dc3545" : "#ffc107"
                  }`
                }}
              >
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
                  <div>
                    <h3 style={{ margin: "0 0 5px 0", color: "#212529" }}>
                      Request ID: <span style={{ fontFamily: "monospace" }}>{req.requestId}</span>
                    </h3>
                    <p style={{ margin: "0 0 10px 0", color: "#6c757d", fontSize: "0.9em" }}>
                      User ID: {req.userId} | Source ID: {req.sourceId || "N/A"} | Type: {req.requestType} | Submitted: {req.createdAt}
                    </p>
                  </div>
                  <span
                    style={{
                      padding: "4px 10px",
                      borderRadius: "12px",
                      fontSize: "0.8em",
                      fontWeight: "bold",
                      color: "white",
                      background: req.status === "APPROVED" ? "#28a745" : req.status === "REJECTED" ? "#dc3545" : "#fd7e14"
                    }}
                  >
                    {req.status}
                  </span>
                </div>

                {/* Sample Metadata / Candidate Mapping Section */}
                {req.sampleMetadata && (
                  <details style={{ marginTop: "10px", background: "#f8f9fa", padding: "10px", borderRadius: "6px" }}>
                    <summary style={{ cursor: "pointer", fontWeight: "600", color: "#0056b3" }}>
                      View Metadata & Candidate Field Mappings
                    </summary>
                    <pre style={{ background: "#eef2f7", padding: "10px", borderRadius: "4px", fontSize: "0.85em", overflowX: "auto" }}>
                      {req.sampleMetadata}
                    </pre>

                    {/* Interactive Candidate Mapping Editor */}
                    {req.status === "SUBMITTED" && (
                      <div style={{ marginTop: "10px" }}>
                        {editingRequestId === req.requestId ? (
                          <div style={{ background: "#fff", padding: "12px", borderRadius: "6px", border: "1px solid #ced4da" }}>
                            <label style={{ fontWeight: "600", fontSize: "0.9em", display: "block", marginBottom: "5px" }}>
                              Edit Candidate Mapping JSON:
                            </label>
                            <textarea
                              rows={8}
                              value={editingMappingJson}
                              onChange={(e) => setEditingMappingJson(e.target.value)}
                              style={{
                                width: "100%",
                                fontFamily: "monospace",
                                fontSize: "0.85em",
                                padding: "8px",
                                borderRadius: "4px",
                                border: "1px solid #ccc"
                              }}
                            />
                            <div style={{ marginTop: "8px", display: "flex", gap: "8px" }}>
                              <button
                                onClick={() => handleSaveCandidateMapping(req.requestId)}
                                disabled={saveMappingLoading}
                                style={{ background: "#0056b3", color: "white", border: "none", padding: "6px 12px", borderRadius: "4px", cursor: "pointer" }}
                              >
                                {saveMappingLoading ? "Saving…" : "Save Candidate Mapping"}
                              </button>
                              <button
                                onClick={() => setEditingRequestId(null)}
                                style={{ background: "#6c757d", color: "white", border: "none", padding: "6px 12px", borderRadius: "4px", cursor: "pointer" }}
                              >
                                Cancel
                              </button>
                            </div>
                          </div>
                        ) : (
                          <button
                            onClick={() => startEditingMapping(req)}
                            style={{ background: "#17a2b8", color: "white", border: "none", padding: "5px 10px", borderRadius: "4px", cursor: "pointer", fontSize: "0.85em" }}
                          >
                            ✏️ Edit Candidate Field Mapping
                          </button>
                        )}
                      </div>
                    )}
                  </details>
                )}

                {/* Approval Actions */}
                {req.status === "SUBMITTED" && (
                  <div style={{ marginTop: "15px", display: "flex", gap: "10px" }}>
                    <button
                      onClick={() => handleDecision(req.requestId, "APPROVED")}
                      disabled={actionLoading === req.requestId}
                      style={{ background: "#28a745", color: "white", border: "none", padding: "8px 16px", borderRadius: "4px", fontWeight: "600", cursor: "pointer" }}
                    >
                      {actionLoading === req.requestId ? "Processing…" : "✅ Approve Request & Activate Key"}
                    </button>

                    <button
                      onClick={() => handleDecision(req.requestId, "REJECTED")}
                      disabled={actionLoading === req.requestId}
                      style={{ background: "#dc3545", color: "white", border: "none", padding: "8px 16px", borderRadius: "4px", fontWeight: "600", cursor: "pointer" }}
                    >
                      {actionLoading === req.requestId ? "Processing…" : "❌ Reject Request"}
                    </button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export default AdminDashboardPage;
