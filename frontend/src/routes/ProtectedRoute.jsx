import { Navigate } from "react-router-dom";

/**
 * Wraps child routes that require authentication.
 * Redirects unauthenticated users to /login, users without an allowed role to /onboard, and, with
 * requireSuperAdmin, regular admins to /admin. This only decides what to show: the server checks every request.
 */
function ProtectedRoute({ children, allowedRoles, requireSuperAdmin = false }) {
  const token = localStorage.getItem("token");
  const role = localStorage.getItem("role") || "USER";

  if (!token) {
    return <Navigate to="/login" replace />;
  }

  if (allowedRoles && allowedRoles.length > 0 && !allowedRoles.includes(role)) {
    return <Navigate to="/onboard" replace />;
  }

  if (requireSuperAdmin && localStorage.getItem("superAdmin") !== "true") {
    return <Navigate to="/admin" replace />;
  }

  return children;
}

export default ProtectedRoute;
