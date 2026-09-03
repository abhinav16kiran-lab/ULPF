import { useState } from "react";
import { useNavigate } from "react-router-dom";
import client from "../api/client";

function LoginPage() {
  const navigate = useNavigate();

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState("ADMIN");
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  // ASSUMPTION: role is a dropdown with all three enum values from Role.java
  // — confirm with backend team whether this is correct before merging
  const ROLES = ["ADMIN", "VENDOR", "USER"];

  async function handleSubmit(e) {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const response = await client.post("/v1/login", {
        username,
        password,
        role,
      });

      // Store auth data in localStorage
      localStorage.setItem("token", response.data.token);
      localStorage.setItem("username", response.data.username);
      localStorage.setItem("role", response.data.role);

      // Navigate based on role
      if (response.data.role === "ADMIN") {
        navigate("/admin");
      } else {
        navigate("/onboard");
      }
    } catch (err) {
      // Error response may be plain text or JSON — handle both
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
      <h1>Login</h1>
      <form onSubmit={handleSubmit}>
        <div>
          <label htmlFor="login-username">Username</label>
          <br />
          <input
            id="login-username"
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
          />
        </div>
        <div>
          <label htmlFor="login-password">Password</label>
          <br />
          <input
            id="login-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        <div>
          <label htmlFor="login-role">Role</label>
          <br />
          <select
            id="login-role"
            value={role}
            onChange={(e) => setRole(e.target.value)}
          >
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
        </div>
        <br />
        <button type="submit" disabled={loading}>
          {loading ? "Logging in…" : "Login"}
        </button>
      </form>
      {error && (
        <p style={{ color: "red" }}>{error}</p>
      )}
    </div>
  );
}

export default LoginPage;
