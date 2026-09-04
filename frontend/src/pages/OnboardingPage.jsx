import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import client from "../api/client";
import LogoutButton from "../components/LogoutButton";

function OnboardingPage() {
  const navigate = useNavigate();

  const [vendorName, setVendorName] = useState("");
  const [sourceName, setSourceName] = useState("");
  const [sourceType, setSourceType] = useState("");
  const [sampleLogFile, setSampleLogFile] = useState(null);
  const [schemaFile, setSchemaFile] = useState(null);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const username = localStorage.getItem("username");

  // If username is missing, redirect to login
  useEffect(() => {
    if (!username) {
      navigate("/login");
    }
  }, [username, navigate]);

  async function handleSubmit(e) {
    e.preventDefault();
    setError(null);
    setResult(null);
    setLoading(true);

    const formData = new FormData();
    formData.append("vendorName", vendorName);
    formData.append("sourceName", sourceName);
    formData.append("sourceType", sourceType);

    if (sampleLogFile) {
      formData.append("sampleLogFile", sampleLogFile);
    }
    if (schemaFile) {
      formData.append("schemaFile", schemaFile);
    }

    try {
      // Do NOT manually set Content-Type — let axios/browser set the multipart boundary
      const response = await client.post(
        `/v1/onboard/${username}`,
        formData,
      );

      setResult({
        requestId: response.data.requestId,
        sourceId: response.data.sourceId,
        vendorId: response.data.vendorId,
        apiKey: response.data.apiKey,
        status: response.data.status,
        message: response.data.message,
      });
    } catch (err) {
      // Error response is JSON { "error": string }
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
    return null; // Will redirect via useEffect
  }

  const ACCEPTED_FILE_TYPES = ".log,.csv,.json,.txt";

  return (
    <div>
      <LogoutButton />
      <h1>Onboarding</h1>
      <p>Submitting as: <strong>{username}</strong></p>

      <form onSubmit={handleSubmit}>
        <div>
          <label htmlFor="onboard-vendor-name">Vendor Name (required)</label>
          <br />
          <input
            id="onboard-vendor-name"
            type="text"
            value={vendorName}
            onChange={(e) => setVendorName(e.target.value)}
            required
          />
        </div>
        <div>
          <label htmlFor="onboard-source-name">Source Name (required)</label>
          <br />
          <input
            id="onboard-source-name"
            type="text"
            value={sourceName}
            onChange={(e) => setSourceName(e.target.value)}
            required
          />
        </div>
        <div>
          <label htmlFor="onboard-source-type">Source Type (required)</label>
          <br />
          <input
            id="onboard-source-type"
            type="text"
            value={sourceType}
            onChange={(e) => setSourceType(e.target.value)}
            required
          />
        </div>
        <div>
          <label htmlFor="onboard-sample-log">Sample Log File (optional)</label>
          <br />
          <input
            id="onboard-sample-log"
            type="file"
            accept={ACCEPTED_FILE_TYPES}
            onChange={(e) => setSampleLogFile(e.target.files[0] || null)}
          />
        </div>
        <div>
          <label htmlFor="onboard-schema">Schema File (optional)</label>
          <br />
          <input
            id="onboard-schema"
            type="file"
            accept={ACCEPTED_FILE_TYPES}
            onChange={(e) => setSchemaFile(e.target.files[0] || null)}
          />
        </div>
        <br />
        <button type="submit" disabled={loading}>
          {loading ? "Submitting…" : "Submit Onboarding Request"}
        </button>
      </form>

      {result && (
        <div style={{ marginTop: "20px", padding: "15px", border: "1px solid #4CAF50", borderRadius: "5px" }}>
          <h2>Request Submitted Successfully</h2>
          <p><strong>Request ID:</strong> {result.requestId}</p>
          <p><strong>Source ID:</strong> {result.sourceId}</p>
          <p><strong>Status:</strong> {result.status}</p>

          {result.apiKey && (
            <div style={{ background: "#f4f4f4", padding: "10px", borderRadius: "4px", margin: "10px 0" }}>
              <p style={{ color: "#d9534f", fontWeight: "bold", margin: "0 0 5px 0" }}>
                ⚠️ Save your API Key now! For security reasons, it will not be shown again:
              </p>
              <code style={{ background: "#e0e0e0", padding: "4px 8px", borderRadius: "3px", fontSize: "1.1em" }}>
                {result.apiKey}
              </code>
            </div>
          )}

          {result.message && <p>{result.message}</p>}
        </div>
      )}

      {error && (
        <p style={{ color: "red" }}>{error}</p>
      )}
    </div>
  );
}

export default OnboardingPage;
