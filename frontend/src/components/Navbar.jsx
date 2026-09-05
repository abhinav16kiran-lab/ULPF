import { useNavigate, Link, useLocation } from "react-router-dom";

function Navbar() {
  const navigate = useNavigate();
  const location = useLocation();
  const role = localStorage.getItem("role") || "USER";
  const username = localStorage.getItem("username") || "";

  function handleLogout() {
    localStorage.clear();
    navigate("/login");
  }

  const linkStyle = (path) => ({
    marginRight: "15px",
    textDecoration: "none",
    fontWeight: location.pathname === path ? "bold" : "normal",
    color: location.pathname === path ? "#0066cc" : "#333",
    borderBottom: location.pathname === path ? "2px solid #0066cc" : "none",
    paddingBottom: "4px"
  });

  return (
    <header style={{
      display: "flex",
      justifyContent: "space-between",
      alignItems: "center",
      padding: "12px 20px",
      background: "#f0f4f8",
      borderBottom: "1px solid #d0d7de",
      marginBottom: "20px"
    }}>
      <div style={{ display: "flex", alignItems: "center" }}>
        <strong style={{ fontSize: "1.2rem", marginRight: "25px", color: "#092540" }}>ULPF Platform</strong>
        <nav>
          {role === "ADMIN" && (
            <Link to="/admin" style={linkStyle("/admin")}>
              Admin Dashboard
            </Link>
          )}
          <Link to="/onboard" style={linkStyle("/onboard")}>
            Onboarding
          </Link>
          <Link to="/notifications" style={linkStyle("/notifications")}>
            Notifications
          </Link>
          <Link to="/analytics" style={linkStyle("/analytics")}>
            Analytics Console
          </Link>
        </nav>
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
        <span style={{ fontSize: "0.9em", color: "#555" }}>
          <strong>@{username}</strong> ({role})
        </span>
        <button
          onClick={handleLogout}
          style={{
            background: "#d9534f",
            color: "white",
            border: "none",
            padding: "6px 14px",
            borderRadius: "4px",
            cursor: "pointer",
            fontSize: "0.85em"
          }}
        >
          Logout
        </button>
      </div>
    </header>
  );
}

export default Navbar;
