import { useState, useEffect, useCallback } from "react";
import client from "../api/client";
import LogoutButton from "../components/LogoutButton";

function AdminDashboardPage() {
  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [actionLoading, setActionLoading] = useState(null); // requestId being acted on
  const [actionError, setActionError] = useState(null);

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

    try {
      await client.put(`/v1/admin/onboard/${requestId}`, { decision });
      // Re-fetch the full list after a successful decision
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

  return (
    <div>
      <LogoutButton />
      <h1>Admin Dashboard</h1>

      {loading && <p>Loading…</p>}
      {error && <p style={{ color: "red" }}>{error}</p>}

      {!loading && !error && requests.length === 0 && (
        <p>No onboarding requests found.</p>
      )}

      {!loading && !error && requests.length > 0 && (
        <ul>
          {requests.map((req) => (
            <li key={req.requestId} style={{ marginBottom: "15px", padding: "10px", border: "1px solid #ccc", borderRadius: "5px" }}>
              <p>
                <strong>Request ID:</strong> {req.requestId}<br />
                <strong>User ID:</strong> {req.userId}<br />
                <strong>Source ID:</strong> {req.sourceId || "N/A"}<br />
                <strong>Request Type:</strong> {req.requestType}<br />
                <strong>Status:</strong> <span style={{ fontWeight: "bold", color: req.status === "APPROVED" ? "green" : req.status === "REJECTED" ? "red" : "orange" }}>{req.status}</span><br />
                <strong>Created At:</strong> {req.createdAt}
              </p>
              {req.sampleMetadata && (
                <details style={{ marginBottom: "10px" }}>
                  <summary style={{ cursor: "pointer", color: "#0066cc" }}>View Sample Metadata</summary>
                  <pre style={{ background: "#f8f9fa", padding: "8px", borderRadius: "4px", fontSize: "0.85em", overflowX: "auto" }}>
                    {req.sampleMetadata}
                  </pre>
                </details>
              )}
              {req.status === "SUBMITTED" && (
                <div>
                  <button
                    onClick={() => handleDecision(req.requestId, "APPROVED")}
                    disabled={actionLoading === req.requestId}
                    style={{ background: "#4CAF50", color: "white", border: "none", padding: "6px 12px", borderRadius: "4px", cursor: "pointer" }}
                  >
                    {actionLoading === req.requestId ? "Processing…" : "Approve"}
                  </button>
                  {" "}
                  <button
                    onClick={() => handleDecision(req.requestId, "REJECTED")}
                    disabled={actionLoading === req.requestId}
                    style={{ background: "#f44336", color: "white", border: "none", padding: "6px 12px", borderRadius: "4px", cursor: "pointer" }}
                  >
                    {actionLoading === req.requestId ? "Processing…" : "Reject"}
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      {actionError && (
        <p style={{ color: "red" }}>Action error: {actionError}</p>
      )}
    </div>
  );
}

export default AdminDashboardPage;
