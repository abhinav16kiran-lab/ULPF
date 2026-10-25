import { useState, useEffect } from "react";
import client from "../api/client";
import Navbar from "../components/Navbar";
import EmptyState from "../components/EmptyState";

// ── Vendor Webhook Integration Card ──────────────────────────────────
function WebhookIntegrationCard() {
  const [webhookData, setWebhookData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [error, setError] = useState(null);
  const [successMsg, setSuccessMsg] = useState(null);
  const [showLogs, setShowLogs] = useState(false);

  // Form state
  const [webhookUrl, setWebhookUrl] = useState("");
  const [secretKey, setSecretKey] = useState("");

  // Test result
  const [testResult, setTestResult] = useState(null);

  async function fetchWebhook() {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get("/v1/vendor/webhook");
      setWebhookData(res.data);
      if (res.data.configured && res.data.webhookUrl) {
        setWebhookUrl(res.data.webhookUrl);
        setSecretKey(""); // Don't prefill secret
      }
    } catch (err) {
      if (err.response?.status !== 404) {
        setError(err.response?.data?.error || err.message || "Failed to load webhook config");
      }
      setWebhookData({ configured: false });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { fetchWebhook(); }, []);

  async function handleSave(e) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setSuccessMsg(null);
    setTestResult(null);
    try {
      const body = { webhookUrl };
      if (secretKey && secretKey.trim()) body.secretKey = secretKey.trim();
      const res = await client.put("/v1/vendor/webhook", body);
      setWebhookData(res.data);
      setSuccessMsg("Webhook configuration saved successfully.");
      setTimeout(() => setSuccessMsg(null), 4000);
    } catch (err) {
      setError(err.response?.data?.error || err.message || "Failed to save webhook");
    } finally {
      setSaving(false);
    }
  }

  async function handleTestPing() {
    setTesting(true);
    setError(null);
    setSuccessMsg(null);
    setTestResult(null);
    try {
      const res = await client.post("/v1/vendor/webhook/test");
      setTestResult(res.data);
      // Refresh to get updated status and logs
      await fetchWebhook();
    } catch (err) {
      setTestResult({
        success: false,
        statusCode: 0,
        latencyMs: 0,
        errorMessage: err.response?.data?.error || err.message || "Test ping failed"
      });
    } finally {
      setTesting(false);
    }
  }

  async function handleRemove() {
    if (!window.confirm("Remove webhook configuration? Alert deliveries to this endpoint will stop immediately.")) return;
    setRemoving(true);
    setError(null);
    setSuccessMsg(null);
    setTestResult(null);
    try {
      await client.delete("/v1/vendor/webhook");
      setWebhookData({ configured: false });
      setWebhookUrl("");
      setSecretKey("");
      setSuccessMsg("Webhook configuration removed.");
      setTimeout(() => setSuccessMsg(null), 4000);
    } catch (err) {
      setError(err.response?.data?.error || err.message || "Failed to remove webhook");
    } finally {
      setRemoving(false);
    }
  }

  // Status badge rendering
  const getStatusBadge = (status, lastStatusCode) => {
    if (status === "HEALTHY") {
      return (
        <span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-bold bg-emerald-50 text-emerald-700 border border-emerald-200 gap-1.5">
          <span className="w-2 h-2 rounded-full bg-emerald-500"></span>
          HEALTHY{lastStatusCode ? ` (${lastStatusCode})` : ""}
        </span>
      );
    }
    if (status === "FAILING") {
      return (
        <span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-bold bg-rose-50 text-rose-700 border border-rose-200 gap-1.5">
          <span className="w-2 h-2 rounded-full bg-rose-500 animate-pulse"></span>
          FAILING{lastStatusCode ? ` (${lastStatusCode})` : ""}
        </span>
      );
    }
    return (
      <span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-bold bg-slate-100 text-slate-600 border border-slate-200 gap-1.5">
        <span className="w-2 h-2 rounded-full bg-slate-400"></span>
        UNTESTED
      </span>
    );
  };

  const formatTimestamp = (rawTs) => {
    if (!rawTs) return "—";
    try {
      const d = new Date(rawTs);
      if (isNaN(d.getTime())) return String(rawTs);
      return d.toLocaleString("en-US", {
        month: "short", day: "numeric",
        hour: "2-digit", minute: "2-digit"
      });
    } catch (e) {
      return String(rawTs);
    }
  };

  if (loading) {
    return (
      <div className="bg-white rounded-2xl border border-slate-200/80 shadow-sm p-6 mb-8 animate-pulse">
        <div className="h-6 bg-slate-200 rounded w-1/3 mb-4"></div>
        <div className="h-4 bg-slate-100 rounded w-2/3 mb-3"></div>
        <div className="h-10 bg-slate-100 rounded w-full"></div>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-2xl border border-slate-200/80 shadow-sm p-6 mb-8">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 mb-5">
        <div>
          <h2 className="text-lg font-extrabold text-slate-900 tracking-tight flex items-center gap-2">
            <svg className="w-5 h-5 text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
            Out-of-Band Webhook Integration
          </h2>
          <p className="text-xs text-slate-500 mt-0.5">
            Route ingestion failure and schema drift alerts to Slack, Discord, PagerDuty, or any HTTP endpoint.
          </p>
        </div>
        {webhookData?.configured && getStatusBadge(webhookData.status, webhookData.lastStatusCode)}
      </div>

      {/* Error / Success banners */}
      {error && (
        <div className="mb-4 p-3 rounded-xl bg-rose-50 border border-rose-200 text-rose-700 text-xs font-medium flex items-center gap-2">
          <svg className="w-4 h-4 text-rose-500 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          {error}
        </div>
      )}
      {successMsg && (
        <div className="mb-4 p-3 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-700 text-xs font-medium flex items-center gap-2">
          <svg className="w-4 h-4 text-emerald-500 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7" />
          </svg>
          {successMsg}
        </div>
      )}

      {/* Test Result Banner */}
      {testResult && (
        <div className={`mb-4 p-3 rounded-xl border text-xs font-medium flex items-center gap-2 ${
          testResult.success
            ? "bg-emerald-50 border-emerald-200 text-emerald-700"
            : "bg-rose-50 border-rose-200 text-rose-700"
        }`}>
          {testResult.success ? (
            <>
              <svg className="w-4 h-4 text-emerald-500 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7" />
              </svg>
              Test ping successful — HTTP {testResult.statusCode} in {testResult.latencyMs}ms
            </>
          ) : (
            <>
              <svg className="w-4 h-4 text-rose-500 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
              Test ping failed{testResult.statusCode > 0 ? ` — HTTP ${testResult.statusCode}` : ""}{testResult.errorMessage ? `: ${testResult.errorMessage}` : ""}
            </>
          )}
        </div>
      )}

      {/* Configuration Form */}
      <form onSubmit={handleSave} className="space-y-4">
        <div>
          <label htmlFor="webhook-url-input" className="block text-xs font-bold text-slate-700 mb-1.5">
            Webhook URL
          </label>
          <input
            id="webhook-url-input"
            type="url"
            placeholder="https://hooks.slack.com/services/T00/B00/XXXXX"
            value={webhookUrl}
            onChange={(e) => setWebhookUrl(e.target.value)}
            required
            className="w-full px-3.5 py-2.5 text-sm bg-slate-50 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-teal-500 font-mono text-slate-800 placeholder-slate-400"
          />
        </div>

        <div>
          <label htmlFor="webhook-secret-input" className="block text-xs font-bold text-slate-700 mb-1.5">
            Secret Key <span className="font-normal text-slate-400">(optional, for HMAC-SHA256 signature verification)</span>
          </label>
          <input
            id="webhook-secret-input"
            type="password"
            placeholder={webhookData?.configured && webhookData?.maskedSecret ? webhookData.maskedSecret : "my-shared-hmac-secret"}
            value={secretKey}
            onChange={(e) => setSecretKey(e.target.value)}
            className="w-full px-3.5 py-2.5 text-sm bg-slate-50 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-teal-500 font-mono text-slate-800 placeholder-slate-400"
          />
          {webhookData?.configured && webhookData?.maskedSecret && (
            <p className="text-[11px] text-slate-400 mt-1 font-mono">
              Current secret: {webhookData.maskedSecret} — leave blank to keep existing
            </p>
          )}
        </div>

        {/* Action Buttons */}
        <div className="flex flex-wrap items-center gap-3 pt-1">
          <button
            type="submit"
            disabled={saving || !webhookUrl.trim()}
            className="inline-flex items-center gap-2 px-5 py-2.5 bg-teal-600 hover:bg-teal-700 text-white font-bold text-xs rounded-xl shadow-sm hover:shadow transition-all active:scale-95 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {saving ? (
              <>
                <svg className="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
                </svg>
                Saving...
              </>
            ) : "Save Configuration"}
          </button>

          {webhookData?.configured && (
            <button
              type="button"
              onClick={handleTestPing}
              disabled={testing}
              className="inline-flex items-center gap-2 px-4 py-2.5 bg-white hover:bg-slate-50 border border-slate-300 text-slate-700 font-bold text-xs rounded-xl shadow-sm transition-all active:scale-95 disabled:opacity-50"
            >
              {testing ? (
                <>
                  <svg className="w-4 h-4 animate-spin text-slate-500" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
                  </svg>
                  Pinging...
                </>
              ) : (
                <>
                  <svg className="w-4 h-4 text-slate-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
                  </svg>
                  Send Test Ping
                </>
              )}
            </button>
          )}

          {webhookData?.configured && (
            <button
              type="button"
              onClick={handleRemove}
              disabled={removing}
              className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-semibold text-rose-600 hover:text-rose-700 hover:bg-rose-50 rounded-xl transition-all"
            >
              {removing ? "Removing..." : "Remove Webhook"}
            </button>
          )}
        </div>
      </form>

      {/* Recent Deliveries Accordion */}
      {webhookData?.configured && webhookData?.recentLogs && webhookData.recentLogs.length > 0 && (
        <div className="mt-6 border-t border-slate-100 pt-4">
          <button
            type="button"
            onClick={() => setShowLogs(!showLogs)}
            className="flex items-center gap-2 text-xs font-bold text-slate-600 hover:text-slate-800 transition-colors"
          >
            <svg className={`w-4 h-4 transition-transform ${showLogs ? "rotate-90" : ""}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 5l7 7-7 7" />
            </svg>
            Recent Deliveries ({webhookData.recentLogs.length})
          </button>

          {showLogs && (
            <div className="mt-3 overflow-x-auto">
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b border-slate-200">
                    <th className="text-left py-2 pr-4 font-bold text-slate-500 uppercase tracking-wider">Alert Type</th>
                    <th className="text-left py-2 pr-4 font-bold text-slate-500 uppercase tracking-wider">Status</th>
                    <th className="text-left py-2 pr-4 font-bold text-slate-500 uppercase tracking-wider">Latency</th>
                    <th className="text-left py-2 font-bold text-slate-500 uppercase tracking-wider">Time</th>
                  </tr>
                </thead>
                <tbody>
                  {webhookData.recentLogs.map((log, i) => (
                    <tr key={i} className="border-b border-slate-100 last:border-0">
                      <td className="py-2 pr-4 font-mono font-semibold text-slate-700">{log.alertType}</td>
                      <td className="py-2 pr-4">
                        <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-md font-bold ${
                          log.success
                            ? "bg-emerald-50 text-emerald-700 border border-emerald-200"
                            : "bg-rose-50 text-rose-700 border border-rose-200"
                        }`}>
                          {log.success ? "✓" : "✗"} {log.statusCode || "ERR"}
                        </span>
                      </td>
                      <td className="py-2 pr-4 font-mono text-slate-600">{log.latencyMs != null ? `${log.latencyMs}ms` : "—"}</td>
                      <td className="py-2 font-mono text-slate-500">{formatTimestamp(log.attemptedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Consecutive failures warning */}
      {webhookData?.configured && webhookData?.consecutiveFails > 0 && (
        <div className="mt-4 p-3 rounded-xl bg-amber-50 border border-amber-200 text-amber-700 text-xs font-medium flex items-center gap-2">
          <svg className="w-4 h-4 text-amber-500 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L3.732 16.5c-.77.833.192 2.5 1.732 2.5z" />
          </svg>
          {webhookData.consecutiveFails} consecutive delivery failure{webhookData.consecutiveFails > 1 ? "s" : ""} detected. Please verify your endpoint is reachable.
        </div>
      )}
    </div>
  );
}


// ── Main Notifications Page ──────────────────────────────────────────
function NotificationsPage() {
  const [notifications, setNotifications] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [activeTab, setActiveTab] = useState("ALL"); // 'ALL' | 'UNREAD' | 'SECURITY' | 'SYSTEM'
  const [searchQuery, setSearchQuery] = useState("");
  const [markingAll, setMarkingAll] = useState(false);

  const userRole = localStorage.getItem("role");

  async function fetchNotifications() {
    setLoading(true);
    setError(null);
    try {
      const response = await client.get("/v1/notifications");
      setNotifications(response.data.notifications || []);
    } catch (err) {
      console.warn("Notifications API fetch error:", err);
      const msg = err.response?.data?.message || err.response?.data?.error || err.message || "Failed to load notifications";
      setError(msg);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    fetchNotifications();
  }, []);

  async function handleMarkAsRead(notificationId) {
    try {
      await client.put(`/v1/notifications/${notificationId}/read`);
      setNotifications((prev) =>
        prev.map((n) => (n.notificationId === notificationId ? { ...n, read: true } : n))
      );
    } catch (err) {
      console.error("Failed to mark notification as read:", err);
    }
  }

  async function handleMarkAllAsRead() {
    setMarkingAll(true);
    try {
      const unreadItems = notifications.filter((n) => !n.read);
      await Promise.all(unreadItems.map((n) => client.put(`/v1/notifications/${n.notificationId}/read`)));
      setNotifications((prev) => prev.map((n) => ({ ...n, read: true })));
    } catch (err) {
      console.error("Failed to mark all notifications as read:", err);
    } finally {
      setMarkingAll(false);
    }
  }

  const unreadCount = notifications.filter((n) => !n.read).length;

  // Filtered notifications logic
  const filteredNotifications = notifications.filter((n) => {
    // Search query filter
    const matchesSearch =
      !searchQuery ||
      n.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
      n.message.toLowerCase().includes(searchQuery.toLowerCase());

    if (!matchesSearch) return false;

    // Tab filter
    if (activeTab === "UNREAD") return !n.read;
    if (activeTab === "SECURITY") {
      const text = (n.title + " " + n.message).toLowerCase();
      return text.includes("tamper") || text.includes("security") || text.includes("merkle") || text.includes("drift");
    }
    if (activeTab === "SYSTEM") {
      const text = (n.title + " " + n.message).toLowerCase();
      return text.includes("pipeline") || text.includes("onboard") || text.includes("system") || text.includes("ingest");
    }

    return true;
  });

  const getCategoryBadge = (title, message) => {
    const text = (title + " " + message).toLowerCase();
    if (text.includes("tamper") || text.includes("security") || text.includes("merkle") || text.includes("reject")) {
      return {
        label: "SECURITY",
        color: "bg-rose-50 text-rose-700 border-rose-200",
        icon: (
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
          </svg>
        )
      };
    }
    if (text.includes("approve") || text.includes("schema") || text.includes("drift")) {
      return {
        label: "PIPELINE",
        color: "bg-amber-50 text-amber-700 border-amber-200",
        icon: (
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
          </svg>
        )
      };
    }
    return {
      label: "SYSTEM",
      color: "bg-teal-50 text-teal-700 border-teal-200",
      icon: (
        <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
        </svg>
      )
    };
  };

  const formatTimestamp = (rawTs) => {
    if (!rawTs) return "Just now";
    try {
      const d = new Date(rawTs);
      if (isNaN(d.getTime())) return String(rawTs);
      return d.toLocaleString("en-US", {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit"
      });
    } catch (e) {
      return String(rawTs);
    }
  };

  return (
    <div className="min-h-screen bg-[#fcfbf8] text-slate-800 flex flex-col justify-between selection:bg-teal-100 selection:text-teal-900">
      {/* Top Navbar */}
      <Navbar />

      {/* Main Content Container */}
      <main className="w-full max-w-7xl mx-auto px-6 lg:px-12 py-8 flex-1">
        {/* Page Header */}
        <div className="flex flex-col md:flex-row md:items-center justify-between mb-8 gap-4">
          <div>
            <div className="flex items-center gap-3">
              <h1 className="text-3xl font-extrabold text-slate-900 tracking-tight">
                Notifications & System Alerts
              </h1>
              {unreadCount > 0 ? (
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-mono font-bold bg-rose-50 text-rose-700 border border-rose-200">
                  <span className="w-1.5 h-1.5 rounded-full bg-rose-500 mr-1.5 animate-pulse"></span>
                  {unreadCount} Unread
                </span>
              ) : (
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-mono font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200">
                  <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 mr-1.5"></span>
                  All Clear
                </span>
              )}
            </div>
            <p className="text-slate-500 text-sm mt-1">
              Real-time security telemetry, schema drift notices, and onboarding pipeline activity for your account.
            </p>
          </div>

          {/* Action Buttons Header */}
          <div className="flex items-center space-x-3 shrink-0">
            {unreadCount > 0 && (
              <button
                type="button"
                onClick={handleMarkAllAsRead}
                disabled={markingAll}
                className="inline-flex items-center space-x-2 px-4 py-2.5 bg-slate-900 hover:bg-slate-800 text-white font-bold text-xs rounded-xl shadow-sm hover:shadow transition-all shrink-0 active:scale-95 disabled:opacity-50"
              >
                <svg className="w-4 h-4 text-teal-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7" />
                </svg>
                <span>{markingAll ? "Marking All..." : "Mark All as Read"}</span>
              </button>
            )}

            <button
              type="button"
              onClick={fetchNotifications}
              className="inline-flex items-center space-x-2 px-3.5 py-2.5 bg-white hover:bg-slate-50 border border-slate-200 text-slate-700 font-bold text-xs rounded-xl shadow-sm transition-all shrink-0 active:scale-95"
              title="Refresh Notifications"
            >
              <svg className="w-4 h-4 text-slate-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
              </svg>
            </button>
          </div>
        </div>

        {/* ── Vendor Webhook Integration Card (VENDOR users only) ── */}
        {userRole === "VENDOR" && <WebhookIntegrationCard />}

        {/* Filter Strip & Search Card */}
        <div className="bg-white rounded-2xl border border-slate-200/80 shadow-sm p-4 mb-6 flex flex-col sm:flex-row items-center justify-between gap-4">
          {/* Tab Filter Buttons */}
          <div className="flex items-center space-x-1 p-1 bg-slate-100/80 rounded-xl border border-slate-200/60 w-full sm:w-auto overflow-x-auto text-xs font-semibold">
            <button
              type="button"
              onClick={() => setActiveTab("ALL")}
              className={`px-3.5 py-1.5 rounded-lg transition-all flex items-center space-x-1.5 shrink-0 ${
                activeTab === "ALL" ? "bg-white text-slate-900 shadow-sm font-bold" : "text-slate-600 hover:text-slate-900"
              }`}
            >
              <span>All Alerts</span>
              <span className="ml-1 text-[10px] px-1.5 py-0.2 rounded-full bg-slate-200 text-slate-700 font-mono">
                {notifications.length}
              </span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab("UNREAD")}
              className={`px-3.5 py-1.5 rounded-lg transition-all flex items-center space-x-1.5 shrink-0 ${
                activeTab === "UNREAD" ? "bg-white text-slate-900 shadow-sm font-bold" : "text-slate-600 hover:text-slate-900"
              }`}
            >
              <span>Unread</span>
              {unreadCount > 0 && (
                <span className="ml-1 text-[10px] px-1.5 py-0.2 rounded-full bg-rose-500 text-white font-mono font-bold">
                  {unreadCount}
                </span>
              )}
            </button>

            <button
              type="button"
              onClick={() => setActiveTab("SECURITY")}
              className={`px-3.5 py-1.5 rounded-lg transition-all flex items-center space-x-1.5 shrink-0 ${
                activeTab === "SECURITY" ? "bg-white text-slate-900 shadow-sm font-bold" : "text-slate-600 hover:text-slate-900"
              }`}
            >
              <span>Security</span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab("SYSTEM")}
              className={`px-3.5 py-1.5 rounded-lg transition-all flex items-center space-x-1.5 shrink-0 ${
                activeTab === "SYSTEM" ? "bg-white text-slate-900 shadow-sm font-bold" : "text-slate-600 hover:text-slate-900"
              }`}
            >
              <span>System</span>
            </button>
          </div>

          {/* Search Bar Input */}
          <div className="relative w-full sm:w-72">
            <svg className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              placeholder="Search notifications..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-9 pr-4 py-2 text-xs bg-slate-50 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-teal-500 font-sans text-slate-800 placeholder-slate-400"
            />
          </div>
        </div>

        {/* Error Notice */}
        {error && (
          <div className="mb-6 p-4 rounded-2xl bg-rose-50 border border-rose-200 text-rose-700 text-sm font-medium flex items-center space-x-2">
            <svg className="w-5 h-5 text-rose-500 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            <span>{error}</span>
          </div>
        )}

        {/* Loading Skeleton View */}
        {loading && (
          <div className="space-y-3">
            {[1, 2, 3].map((i) => (
              <div key={i} className="bg-white rounded-2xl border border-slate-200/80 p-5 animate-pulse flex items-start space-x-4">
                <div className="w-10 h-10 rounded-xl bg-slate-200 shrink-0"></div>
                <div className="flex-1 space-y-2">
                  <div className="h-4 bg-slate-200 rounded w-1/3"></div>
                  <div className="h-3 bg-slate-100 rounded w-3/4"></div>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Empty State View */}
        {!loading && filteredNotifications.length === 0 && (
          <EmptyState
            title="All Caught Up!"
            description={`There are no ${activeTab !== "ALL" ? activeTab.toLowerCase() : ""} notifications or system alerts matching your current filter.`}
          />
        )}

        {/* Notifications Feed List */}
        {!loading && filteredNotifications.length > 0 && (
          <div className="space-y-3">
            {filteredNotifications.map((item) => {
              const badge = getCategoryBadge(item.title, item.message);
              return (
                <div
                  key={item.notificationId || item.id}
                  className={`bg-white rounded-2xl border transition-all p-5 shadow-sm hover:shadow flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 ${
                    !item.read
                      ? "border-teal-300/80 bg-gradient-to-r from-teal-50/30 via-white to-white"
                      : "border-slate-200/80"
                  }`}
                >
                  <div className="flex items-start space-x-4 flex-1">
                    {/* Category Icon Badge */}
                    <div className={`w-10 h-10 rounded-xl border flex items-center justify-center shrink-0 ${badge.color}`}>
                      {badge.icon}
                    </div>

                    <div className="space-y-1 flex-1">
                      <div className="flex items-center space-x-2 flex-wrap gap-y-1">
                        <span className={`inline-flex items-center space-x-1 px-2 py-0.5 rounded-md text-[10px] font-mono font-bold border ${badge.color}`}>
                          {badge.label}
                        </span>

                        <h3 className={`text-sm font-bold tracking-tight ${!item.read ? "text-slate-900 font-extrabold" : "text-slate-700"}`}>
                          {item.title}
                        </h3>

                        {!item.read && (
                          <span className="w-2 h-2 rounded-full bg-teal-500 inline-block animate-pulse" title="Unread"></span>
                        )}
                      </div>

                      <p className="text-xs text-slate-600 leading-relaxed">
                        {item.message}
                      </p>

                      <div className="text-[11px] text-slate-400 font-mono pt-1">
                        {formatTimestamp(item.createdAt)}
                      </div>
                    </div>
                  </div>

                  {/* Mark as Read Action Button */}
                  {!item.read && (
                    <button
                      type="button"
                      onClick={() => handleMarkAsRead(item.notificationId)}
                      className="px-3.5 py-1.5 text-xs font-semibold text-teal-700 hover:text-teal-800 bg-teal-50 hover:bg-teal-100/80 border border-teal-200/80 rounded-xl transition-all shrink-0 active:scale-95 self-end sm:self-center"
                    >
                      Mark as Read
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </main>
    </div>
  );
}

export default NotificationsPage;
