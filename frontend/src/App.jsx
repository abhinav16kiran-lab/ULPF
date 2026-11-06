import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import ProtectedRoute from "./routes/ProtectedRoute";
import LoginPage from "./pages/LoginPage";
import SignupPage from "./pages/SignupPage";
import OnboardingPage from "./pages/OnboardingPage";
import AdminDashboardPage from "./pages/AdminDashboardPage";
import NotificationsPage from "./pages/NotificationsPage";
import AnalyticsPage from "./pages/AnalyticsPage";
import IntegrityConsolePage from "./pages/IntegrityConsolePage";
import SetupPage from "./pages/SetupPage";
import LinkPage from "./pages/LinkPage";
import AdminsPage from "./pages/AdminsPage";

function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Default Root Redirect */}
        <Route path="/" element={<Navigate to="/login" replace />} />

        {/* Public routes */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/setup" element={<SetupPage />} />
        <Route path="/link/:token" element={<LinkPage />} />

        {/* Protected routes */}
        <Route
          path="/onboard"
          element={
            <ProtectedRoute>
              <OnboardingPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin"
          element={
            <ProtectedRoute allowedRoles={["ADMIN"]}>
              <AdminDashboardPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/admins"
          element={
            <ProtectedRoute allowedRoles={["ADMIN"]} requireSuperAdmin>
              <AdminsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/notifications"
          element={
            <ProtectedRoute>
              <NotificationsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/analytics"
          element={
            <ProtectedRoute allowedRoles={["ADMIN"]}>
              <AnalyticsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/integrity"
          element={
            <ProtectedRoute allowedRoles={["ADMIN", "USER", "VENDOR"]}>
              <IntegrityConsolePage />
            </ProtectedRoute>
          }
        />

        {/* Catch-all Wildcard Redirect */}
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
