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
        <ul>
          {notifications.map((item, index) => (
            // TODO: render specific fields once notification schema is documented
            <li key={index}>{JSON.stringify(item)}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default NotificationsPage;
