import axios from "axios";

const client = axios.create({
  baseURL: import.meta.env.VITE_CORE_ENGINE_API_URL,
});

// Attach JWT from localStorage on protected outgoing requests
client.interceptors.request.use((config) => {
  const isAuthEndpoint =
    config.url && (config.url.includes("/v1/login") || config.url.includes("/v1/signup"));

  const token = localStorage.getItem("token");
  if (token && token !== "undefined" && token !== "null" && !isAuthEndpoint) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response Interceptor: Sanitize errors and handle expired auth sessions
client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      const status = error.response.status;
      const data = error.response.data;

      // Clear stale credentials if a protected request fails with 401 Unauthorized
      const isAuthEndpoint =
        error.config?.url &&
        (error.config.url.includes("/v1/login") || error.config.url.includes("/v1/signup"));
      if (status === 401 && !isAuthEndpoint) {
        localStorage.removeItem("token");
        localStorage.removeItem("username");
        localStorage.removeItem("role");
      }

      // Check if response data is an HTML page (like Nginx 502/504 error page)
      const isHtmlResponse =
        typeof data === "string" &&
        (data.trim().startsWith("<") || data.includes("<html"));

      if (isHtmlResponse || status >= 500) {
        let cleanMsg = `Server error (${status}). Please try again later.`;
        if (status === 502) {
          cleanMsg = "Bad Gateway (502). The Core Engine API is currently unreachable.";
        } else if (status === 503) {
          cleanMsg = "Service Unavailable (503). The server is temporarily overloaded or down.";
        } else if (status === 504) {
          cleanMsg = "Gateway Timeout (504). The upstream backend service timed out.";
        }

        // Replace raw HTML response data with clean error message
        error.response.data = { message: cleanMsg, error: cleanMsg };
      }
    } else if (error.request) {
      // Network error (no response received at all)
      error.message = "Unable to connect to ULPF services. Please check your network connection.";
    }

    return Promise.reject(error);
  }
);

export default client;
