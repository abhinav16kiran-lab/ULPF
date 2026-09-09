import { useNavigate, Link, useLocation } from "react-router-dom";
import UlpfLogo from "./UlpfLogo";

function Navbar() {
  const navigate = useNavigate();
  const location = useLocation();
  const role = localStorage.getItem("role") || "USER";
  const username = localStorage.getItem("username") || "";

  function handleLogout() {
    localStorage.clear();
    navigate("/login");
  }

  const navItemClass = (path) =>
    `px-3 py-2 rounded-xl text-xs font-semibold transition-all flex items-center space-x-1.5 ${
      location.pathname === path
        ? "bg-teal-50 text-teal-700 border border-teal-200/80 shadow-sm"
        : "text-slate-600 hover:text-slate-900 hover:bg-slate-100/80"
    }`;

  return (
    <header className="bg-white border-b border-slate-200/80 sticky top-0 z-50 shadow-sm">
      <div className="max-w-7xl mx-auto px-6 lg:px-12 h-16 flex items-center justify-between">
        <div className="flex items-center space-x-8">
          {/* Brand Logo */}
          <Link to={role === "ADMIN" ? "/admin" : "/onboard"} className="flex items-center space-x-2 group hover:opacity-90 transition-opacity">
            <UlpfLogo className="h-8 w-auto" />
          </Link>

          {/* Navigation Links */}
          <nav className="flex items-center space-x-1">
            {role === "ADMIN" && (
              <Link to="/admin" className={navItemClass("/admin")}>
                <span>Admin Dashboard</span>
              </Link>
            )}
            {role !== "ADMIN" && (
              <Link to="/onboard" className={navItemClass("/onboard")}>
                <span>Onboarding</span>
              </Link>
            )}
            <Link to="/notifications" className={navItemClass("/notifications")}>
              <span>Notifications</span>
            </Link>
            {role === "ADMIN" && (
              <Link to="/analytics" className={navItemClass("/analytics")}>
                <span>Analytics Console</span>
              </Link>
            )}
            <Link to="/integrity" className={navItemClass("/integrity")}>
              <span>🔐 Integrity Audit</span>
            </Link>
          </nav>
        </div>

        {/* User Info & Logout */}
        <div className="flex items-center space-x-3">
          <div className="flex items-center space-x-2 px-3 py-1.5 rounded-xl bg-slate-100/80 border border-slate-200/70 text-xs font-mono">
            <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
            <span className="font-bold text-slate-800">@{username}</span>
            <span className="px-1.5 py-0.5 rounded text-[10px] font-semibold bg-slate-200 text-slate-700">
              {role}
            </span>
          </div>

          <button
            onClick={handleLogout}
            className="px-3.5 py-1.5 text-xs font-bold text-rose-600 hover:text-rose-700 bg-rose-50 hover:bg-rose-100/80 border border-rose-200/70 rounded-xl transition-all active:scale-95"
          >
            Logout
          </button>
        </div>
      </div>
    </header>
  );
}

export default Navbar;
