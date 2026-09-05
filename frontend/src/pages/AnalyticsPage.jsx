import { useState } from "react";
import client from "../api/client";
import Navbar from "../components/Navbar";

const AGGREGATIONS = ["COUNT", "AVG", "MIN", "MAX", "SUM"];

function AnalyticsPage() {
  const [table, setTable] = useState("logs_canonical");
  const [column, setColumn] = useState("source_ip");
  const [aggregation, setAggregation] = useState("COUNT");
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e) {
    e.preventDefault();
    setError(null);
    setResult(null);
    setLoading(true);

    try {
      const response = await client.get("/v1/analytics", {
        params: { table, column, aggregation },
      });

      setResult(response.data);
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

  return (
    <div>
      <Navbar />
      <div style={{ padding: "0 20px" }}>
        <h2>Log Analytics & Aggregation Engine</h2>

      <form onSubmit={handleSubmit}>
        <div>
          <label htmlFor="analytics-table">Table</label>
          <br />
          <input
            id="analytics-table"
            type="text"
            value={table}
            onChange={(e) => setTable(e.target.value)}
            required
          />
        </div>
        <div>
          <label htmlFor="analytics-column">Column</label>
          <br />
          <input
            id="analytics-column"
            type="text"
            value={column}
            onChange={(e) => setColumn(e.target.value)}
            required
          />
        </div>
        <div>
          <label htmlFor="analytics-aggregation">Aggregation</label>
          <br />
          <select
            id="analytics-aggregation"
            value={aggregation}
            onChange={(e) => setAggregation(e.target.value)}
          >
            {AGGREGATIONS.map((agg) => (
              <option key={agg} value={agg}>
                {agg}
              </option>
            ))}
          </select>
        </div>
        <br />
        <button type="submit" disabled={loading}>
          {loading ? "Querying…" : "Run Query"}
        </button>
      </form>

      {result && (
        <div>
          <h2>Result</h2>
          <p>
            <strong>{result.aggregation}</strong>({result.table}.{result.column}) = <strong>{result.result}</strong>
          </p>
        </div>
      )}

      {error && (
        <p style={{ color: "red" }}>{error}</p>
      )}
      </div>
    </div>
  );
}

export default AnalyticsPage;
