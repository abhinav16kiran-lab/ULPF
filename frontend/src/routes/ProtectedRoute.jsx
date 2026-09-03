import { Navigate } from "react-router-dom";

/**
 * Wraps child routes that require authentication.
 * Redirects unauthenticated users to /login.
 *
 * TODO: Add role-based access restriction (e.g. admin-only routes).
 */
function ProtectedRoute({ children }) {
  const token = localStorage.getItem("token");

  if (!token) {
    return <Navigate to="/login" replace />;
  }

  return children;
}

export default ProtectedRoute;
