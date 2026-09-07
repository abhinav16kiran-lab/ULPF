import { Navigate } from "react-router-dom";

/**
 * Wraps child routes that require authentication.
 * Redirects unauthenticated users to /login.
 *
 * TODO: Add role-based access restriction (e.g. admin-only routes).
 */
function ProtectedRoute({ children, allowedRoles }) {
  const token = localStorage.getItem("token");
  const role = localStorage.getItem("role") || "USER";

  if (!token) {
    return <Navigate to="/login" replace />;
  }

  if (allowedRoles && allowedRoles.length > 0 && !allowedRoles.includes(role)) {
    return <Navigate to="/onboard" replace />;
  }

  return children;
}

export default ProtectedRoute;
