import { useState, useEffect } from "react";
import client from "../api/client";
import LogoutButton from "../components/LogoutButton";

function NotificationsPage() {
  const [notifications, setNotifications] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    async function fetchNotifications() {
      try {
        const response = await client.get("/v1/notifications");
        setNotifications(response.data.notifications || []);
      } catch (err) {
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

  return (
    <div>
      <LogoutButton />
      <h1>Notifications</h1>

      {loading && <p>Loading…</p>}
      {error && <p style={{ color: "red" }}>{error}</p>}

      {!loading && !error && notifications.length === 0 && (
        <p>No notifications</p>
      )}

      {!loading && !error && notifications.length > 0 && (
        <ul style={{ listStyle: "none", padding: 0 }}>
          {notifications.map((item) => (
            <li
              key={item.notificationId}
              style={{
                marginBottom: "12px",
                padding: "12px",
                borderRadius: "6px",
                border: item.read ? "1px solid #ddd" : "2px solid #0066cc",
                background: item.read ? "#fafafa" : "#f0f7ff",
              }}
            >
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <h3 style={{ margin: "0 0 5px 0", fontSize: "1.1em" }}>{item.title}</h3>
                <span style={{ fontSize: "0.8em", color: "#666" }}>{item.createdAt}</span>
              </div>
              <p style={{ margin: "5px 0 10px 0" }}>{item.message}</p>
              {!item.read && (
                <button
                  onClick={() => handleMarkAsRead(item.notificationId)}
                  style={{
                    background: "#0066cc",
                    color: "white",
                    border: "none",
                    padding: "4px 10px",
                    borderRadius: "4px",
                    cursor: "pointer",
                    fontSize: "0.85em",
                  }}
                >
                  Mark as Read
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default NotificationsPage;
