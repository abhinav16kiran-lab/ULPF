import client from "./client";

/**
 * Admin accounts API: one-time setup, invite/reset links, and the super-admin's Admins screen.
 * Every function resolves to the response body; errors are axios errors (see errorMessage).
 */

export const getSetupStatus = () => client.get("/v1/setup/status").then((r) => r.data);
export const completeSetup = (body) => client.post("/v1/setup", body).then((r) => r.data);

export const describeLink = (token) => client.get(`/v1/links/${encodeURIComponent(token)}`).then((r) => r.data);
export const submitLink = (token, body) => client.post(`/v1/links/${encodeURIComponent(token)}`, body).then(() => undefined);

export const listAdmins = () => client.get("/v1/admin/admins").then((r) => r.data);
export const listPendingLinks = () => client.get("/v1/admin/admins/links").then((r) => r.data);
export const createInvite = (label) => client.post("/v1/admin/admins/invites", { label }).then((r) => r.data);
export const createResetLink = (userId) => client.post(`/v1/admin/admins/${userId}/reset-link`).then((r) => r.data);
export const revokeAdmin = (userId) => client.delete(`/v1/admin/admins/${userId}`).then(() => undefined);
export const cancelLink = (linkId) => client.delete(`/v1/admin/admins/links/${linkId}`).then(() => undefined);

/** The link the super-admin shares: built from the address the browser is on, like the API base. */
export const linkUrl = (token) => `${window.location.origin}/link/${token}`;

/** The server's message for a failed request, or the fallback. */
export function errorMessage(err, fallback) {
  const data = err?.response?.data;
  if (typeof data === "string" && data && !data.startsWith("<")) return data;
  return data?.error || data?.message || fallback;
}

/** Admin password rule, mirrored from the server (CredentialRules) so the form can say it before sending. */
export const ADMIN_PASSWORD_MIN = 12;
export function adminPasswordProblem(password, confirm) {
  if (password.length < ADMIN_PASSWORD_MIN || password.length > 100) return `Use ${ADMIN_PASSWORD_MIN} to 100 characters`;
  if (password !== confirm) return "Passwords do not match";
  return null;
}
