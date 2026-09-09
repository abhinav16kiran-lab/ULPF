import { useState, useEffect } from "react";
import client from "../api/client";
import Navbar from "../components/Navbar";

function NotificationsPage() {
  const [notifications, setNotifications] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [activeTab, setActiveTab] = useState("ALL"); // 'ALL' | 'UNREAD' | 'SECURITY' | 'SYSTEM'
  const [searchQuery, setSearchQuery] = useState("");
  const [markingAll, setMarkingAll] = useState(false);

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
          <div className="bg-white rounded-3xl border border-slate-200/80 p-12 text-center shadow-sm my-4">
            <div className="w-16 h-16 rounded-2xl bg-teal-50 border border-teal-200 text-teal-600 flex items-center justify-center mx-auto mb-4">
              <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <h3 className="text-lg font-bold text-slate-900 mb-1">
              All Caught Up!
            </h3>
            <p className="text-slate-500 text-sm max-w-md mx-auto">
              There are no {activeTab !== "ALL" ? activeTab.toLowerCase() : ""} notifications or system alerts matching your filter.
            </p>
          </div>
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
