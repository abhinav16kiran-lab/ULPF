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
            <li key={req.requestId}>
              <p>
                <strong>Request ID:</strong> {req.requestId}<br />
                <strong>Submitted By:</strong> {req.submittedBy}<br />
                <strong>Vendor:</strong> {req.vendorName}<br />
                <strong>Source:</strong> {req.sourceName}<br />
                <strong>Source Type:</strong> {req.sourceType}<br />
                <strong>Status:</strong> {req.status}<br />
                <strong>Created At:</strong> {req.createdAt}
              </p>
              {req.status === "SUBMITTED" && (
                <div>
                  <button
                    onClick={() => handleDecision(req.requestId, "APPROVED")}
                    disabled={actionLoading === req.requestId}
                  >
                    {actionLoading === req.requestId ? "Processing…" : "Approve"}
                  </button>
                  {" "}
                  <button
                    onClick={() => handleDecision(req.requestId, "REJECTED")}
                    disabled={actionLoading === req.requestId}
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
