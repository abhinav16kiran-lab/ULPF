import { useState, useEffect, useCallback } from "react";
import client from "../api/client";
import Navbar from "../components/Navbar";

function IntegrityConsolePage() {
  const [blocks, setBlocks] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [verifyingBlockId, setVerifyingBlockId] = useState(null);
  const [verificationResults, setVerificationResults] = useState({});
  const [bulkVerifying, setBulkVerifying] = useState(false);
  const [bulkResults, setBulkResults] = useState(null);

  const fetchBlocks = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await client.get("/v1/integrity/blocks");
      setBlocks(response.data.blocks || []);
    } catch (err) {
      if (err.response && err.response.data && err.response.data.error) {
        setError(err.response.data.error);
      } else {
        setError(err.message);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchBlocks();
  }, [fetchBlocks]);

  async function handleVerifyAll() {
    setBulkVerifying(true);
    setBulkResults(null);
    try {
      const response = await client.post("/v1/integrity/verify-all");
      setBulkResults(response.data);
      if (response.data.tamperedBlocks) {
        setVerificationResults((prev) => {
          const updated = { ...prev };
          response.data.tamperedBlocks.forEach((b) => {
            updated[b.blockId] = b;
          });
          return updated;
        });
      }
    } catch (err) {
      setError(err.response?.data?.error || err.message);
    } finally {
      setBulkVerifying(false);
    }
  }

  async function handleVerifyBlock(blockId) {
    setVerifyingBlockId(blockId);
    try {
      const response = await client.post(`/v1/integrity/verify/${blockId}`);
      setVerificationResults((prev) => ({
        ...prev,
        [blockId]: response.data,
      }));
    } catch (err) {
      const errMsg = err.response?.data?.error || err.message;
      setVerificationResults((prev) => ({
        ...prev,
        [blockId]: { status: "ERROR", message: errMsg, isTampered: true },
      }));
    } finally {
      setVerifyingBlockId(null);
    }
  }

  return (
    <div>
      <Navbar />

      <div style={{ padding: "0 20px" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "15px" }}>
          <h2>🔐 Merkle-Tree Batch Tamper-Evidence Audit Console</h2>
          <div style={{ display: "flex", gap: "10px" }}>
            <button
              onClick={handleVerifyAll}
              disabled={bulkVerifying}
              style={{
                background: bulkVerifying ? "#6c757d" : "#0d6efd",
                color: "white",
                border: "none",
                padding: "8px 16px",
                borderRadius: "4px",
                fontWeight: "600",
                cursor: bulkVerifying ? "not-allowed" : "pointer"
              }}
            >
              {bulkVerifying ? "Running Bulk Audit..." : "🛡️ Verify All Blocks"}
            </button>
            <button
              onClick={fetchBlocks}
              style={{
                background: "#0f766e",
                color: "white",
                border: "none",
                padding: "8px 16px",
                borderRadius: "4px",
                fontWeight: "600",
                cursor: "pointer"
              }}
            >
              🔄 Refresh Blocks
            </button>
          </div>
        </div>

        {/* Informational Banner */}
        <div
          style={{
            background: "#f0fdf4",
            borderLeft: "5px solid #16a34a",
            padding: "12px 16px",
            borderRadius: "6px",
            marginBottom: "20px",
            color: "#15803d",
            fontSize: "0.9em"
          }}
        >
          <strong>NTRO Cryptographic Integrity Proof</strong>: Log events are grouped into batch blocks. Every block calculates a <strong>SHA-256 Merkle Root</strong> over raw log payloads and chains its <code>previous_block_hash</code> to form an unbroken forensic hash chain.
        </div>

        {loading && <p>Loading batch integrity blocks…</p>}
        {error && <p style={{ color: "red" }}>{error}</p>}

        {bulkResults && (
          <div
            style={{
              background: bulkResults.tamperedCount > 0 ? "#f8d7da" : "#d1e7dd",
              borderLeft: `5px solid ${bulkResults.tamperedCount > 0 ? "#dc3545" : "#198754"}`,
              padding: "16px",
              borderRadius: "6px",
              marginBottom: "20px",
              color: bulkResults.tamperedCount > 0 ? "#842029" : "#0f5132",
            }}
          >
            <h3 style={{ margin: "0 0 10px 0" }}>Bulk Verification Complete</h3>
            <p style={{ margin: "0 0 5px 0" }}>
              Total Blocks Checked: <strong>{bulkResults.totalBlocksChecked}</strong>
            </p>
            <p style={{ margin: "0 0 5px 0" }}>
              Valid Blocks: <strong>{bulkResults.validCount}</strong>
            </p>
            <p style={{ margin: "0" }}>
              Tampered Blocks Detected: <strong>{bulkResults.tamperedCount}</strong>
            </p>
          </div>
        )}

        {!loading && !error && blocks.length === 0 && (
          <div style={{ background: "#f8f9fa", padding: "20px", borderRadius: "6px", textAlign: "center" }}>
            <p style={{ color: "#666" }}>No batch integrity blocks generated yet. Ingest events at <code>/v1/events</code> to generate cryptographic batch proofs.</p>
          </div>
        )}

        {!loading && !error && blocks.length > 0 && (
          <div style={{ display: "grid", gap: "15px" }}>
            {blocks.map((block) => {
              const verification = verificationResults[block.blockId];
              const isVerifying = verifyingBlockId === block.blockId;

              return (
                <div
                  key={block.blockId}
                  style={{
                    background: "white",
                    padding: "16px",
                    borderRadius: "8px",
                    boxShadow: "0 2px 6px rgba(0,0,0,0.08)",
                    borderLeft: `5px solid ${
                      verification ? (verification.isTampered ? "#dc3545" : "#28a745") : "#0d6efd"
                    }`
                  }}
                >
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
                    <div>
                      <h3 style={{ margin: "0 0 5px 0", color: "#212529" }}>
                        Block #{block.blockId} | Source: <span style={{ fontFamily: "monospace", color: "#0d6efd" }}>{block.sourceId}</span>
                      </h3>
                      <p style={{ margin: "0 0 10px 0", color: "#6c757d", fontSize: "0.85em" }}>
                        Events in Batch: <strong>{block.eventCount}</strong> | First Event: <code>{block.firstEventId}</code> | Created: {block.createdAt}
                      </p>
                    </div>

                    <button
                      onClick={() => handleVerifyBlock(block.blockId)}
                      disabled={isVerifying}
                      style={{
                        background: "#0d6efd",
                        color: "white",
                        border: "none",
                        padding: "6px 14px",
                        borderRadius: "4px",
                        fontWeight: "600",
                        fontSize: "0.85em",
                        cursor: "pointer"
                      }}
                    >
                      {isVerifying ? "Verifying Hashing…" : "🔍 Run Forensic Audit"}
                    </button>
                  </div>

                  {/* Hash Details */}
                  <div style={{ background: "#f8f9fa", padding: "10px", borderRadius: "6px", fontSize: "0.85em", fontFamily: "monospace" }}>
                    <div>
                      <strong style={{ color: "#495057" }}>Merkle Root Hash:</strong>{" "}
                      <span style={{ color: "#0f766e", wordBreak: "break-all" }}>{block.merkleRoot}</span>
                    </div>
                    <div style={{ marginTop: "4px" }}>
                      <strong style={{ color: "#495057" }}>Previous Block Hash:</strong>{" "}
                      <span style={{ color: "#6c757d", wordBreak: "break-all" }}>{block.previousBlockHash}</span>
                    </div>
                  </div>

                  {/* Verification Results Output */}
                  {verification && (
                    <div
                      style={{
                        marginTop: "10px",
                        padding: "10px 12px",
                        borderRadius: "6px",
                        background: verification.isTampered ? "#f8d7da" : "#d1e7dd",
                        color: verification.isTampered ? "#842029" : "#0f5132",
                        fontSize: "0.85em"
                      }}
                    >
                      <strong>Status: {verification.status}</strong> — {verification.message}
                      {verification.computedMerkleRoot && (
                        <div style={{ marginTop: "4px", fontFamily: "monospace" }}>
                          Computed Root: {verification.computedMerkleRoot}
                        </div>
                      )}
                      {verification.databaseTable && (
                        <div style={{ marginTop: "10px", padding: "8px", background: "rgba(255,255,255,0.5)", borderRadius: "4px" }}>
                          <strong>Traceability Data:</strong>
                          <ul style={{ margin: "5px 0 0 0", paddingLeft: "20px", fontSize: "0.95em" }}>
                            <li>Database Table: <code>{verification.databaseTable}</code></li>
                            <li>Start Event ID: <code>{verification.firstEventId}</code></li>
                            <li>End Event ID: <code>{verification.lastEventId}</code></li>
                          </ul>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

export default IntegrityConsolePage;
