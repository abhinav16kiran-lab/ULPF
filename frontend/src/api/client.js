import axios from "axios";

const client = axios.create({
  baseURL: import.meta.env.VITE_CORE_ENGINE_API_URL,
});

// Attach JWT from localStorage on every outgoing request
client.interceptors.request.use((config) => {
  const token = localStorage.getItem("token");
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export default client;
