# Frontend Architecture & Implementation Specification (`frontend`)

This document describes the architecture, page-by-page functionality, design system, route guards, containerization, and production deployment using Nginx for the ULPF React Frontend.

---

## 1. Containerization & Production Deployment Architecture

The frontend is packaged into a production-grade multi-stage container image using **Nginx Alpine** as an edge web server and reverse proxy:

```text
                                [ User Web Browser ]
                                         │
                                         │ HTTP Request (Port 3000)
                                         ▼
                      ┌──────────────────────────────────────┐
                      │    ulpf-frontend (Nginx Container)   │
                      └──────────────────┬───────────────────┘
                                         │
                 ┌───────────────────────┴───────────────────────┐
                 │                                               │
                 ▼                                               ▼
     [ Static Asset Server ]                         [ Reverse Proxy Gateway ]
     - Serves React SPA static bundles               - Proxies /v1/* endpoints
     - Handles HTML5 pushState fallback              - Forwarded to http://core-engine:8080/v1/
     - Location / -> index.html                      - Eliminates CORS overhead in production
```

---

## 2. Design System Alignment (Warm Kinetic)

All application views utilize the tokens established in the team's official **Warm Kinetic** Design System:

* **Canvas & Surface Palette**:
  * Canvas Ground: `#fff8f6` (`bg-surface`)
  * Elevated Card Surface: `#ffffff` (`bg-surface-container-lowest`)
  * Inset Fields & Shelves: `#fbf2f0` (`bg-surface-container-low`) / `#efe6e4` (`bg-surface-container-high`)
* **Brand Accents**:
  * Primary Action / Active: Teal `#14b8a6` (`bg-primary-container`), `#006b5f` (`primary`)
  * Urgent / Alert / Reject: Warm Coral `#ff6b6b` (`bg-secondary-container`), `#ae2f34` (`secondary`)
  * Notice / Highlights: Sunny Yellow `#ffe24c` (`tertiary-fixed`), `#bda400` (`tertiary-container`)
* **Typography & Icons**:
  * Display, Headings & Body: `Plus Jakarta Sans`
  * Code, Raw Logs & API Tokens: `JetBrains Mono`

---

## 3. Page Inventory & Routes

| Path | View Component | Protected Role(s) | Key Capabilities |
| :--- | :--- | :--- | :--- |
| `/login` | `LoginPage.jsx` | Public | Authentication & JWT token acquisition; sends you to `/setup` while no super-admin exists; no pre-filled credentials |
| `/signup` | `SignupPage.jsx` | Public | User registration (always `USER`) |
| `/setup` | `SetupPage.jsx` | Public (once) | One-time setup: step 1 `.env` setup credentials, step 2 the super-admin account |
| `/link/:token` | `LinkPage.jsx` | Public (link token) | Use an admin invite link (username + password) or reset link (new password) |
| `/onboard` | `OnboardingPage.jsx` | Authenticated (server: `USER`, `VENDOR`) | 50/50 split workspace for log sample upload & live payload parsing; under My Active Log Sources, **Create API key** / **Regenerate** (with a confirmation) shows the key once in `ApiKeyReveal` |
| `/admin` | `AdminDashboardPage.jsx` | `ADMIN` | Schema governance, AI mapping review & one-click ClickHouse activation |
| `/admin/admins` | `AdminsPage.jsx` | Super-admin | Invite admins, reset passwords and revoke admins by single-use links; pending links |
| `/notifications` | `NotificationsPage.jsx` | Authenticated | User notification feed and unread toggles (never contains an API key) |
| `/integrity` | `IntegrityConsolePage.jsx` | `ADMIN`, `VENDOR`, `USER` | Cryptographic Merkle tree forensic audit console |
| `/analytics` | `AnalyticsPage.jsx` | `ADMIN` | ClickHouse query builder (Builder Mode, Direct SQL, Lineage) |

---

## 4. Protected Routes & Authentication Flow

Client-side authorization is enforced by `ProtectedRoute.jsx`:

```jsx
<Route
  path="/admin"
  element={
    <ProtectedRoute allowedRoles={["ADMIN"]}>
      <AdminDashboardPage />
    </ProtectedRoute>
  }
/>
```

* Checks `localStorage.getItem("token")`. If missing, redirects immediately to `/login`.
* Checks user role against `allowedRoles`. If unauthorized, redirects user safely.
* With `requireSuperAdmin`, also needs `localStorage.superAdmin === "true"` (set at login from the response's `superAdmin`), else redirects to `/admin`. The navbar shows **Admins** only to the super-admin. These are display decisions only; the server enforces every rule.
* Admin account calls live in `src/api/admins.js`, together with the admin password rule (12-100 characters) mirrored from the server so forms can say it before sending. Screens reuse `AuthLayout.css` (setup, link) and `AdminDashboardPage.css` (Admins), with small additions at the end of each file.
* `components/AuthShowcase.jsx` is the animated falling-log panel on the right of every auth screen (login, sign-up, setup, link). Why one component: the panel was copied line for line into Login and Sign-up, and the first version of Setup and Link left it out. They looked like a different product, and on a fresh install the login page redirects straight to Setup, so the panel seemed to have disappeared. Each of those pages has a test that the panel is shown. Below 1024 px it is hidden on purpose (`AuthLayout.css`).
* **Axios Request Interceptor (`src/api/client.js`)**:
  * Automatically injects `Authorization: Bearer <token>` on protected API endpoints.
  * Explicitly bypasses public endpoints (`/v1/login`, `/v1/signup`, `/v1/setup`, `/v1/links/`) so stale credentials from prior sessions are never transmitted.
  * Discards malformed or `"undefined"` token values.
* **Axios Response Interceptor (`src/api/client.js`)**:
  * Automatically purges invalid or expired tokens (`token`, `username`, `role`, `superAdmin`) from `localStorage` upon receiving a `401 Unauthorized` on protected endpoints, ensuring clean session recovery.
  * Sanitizes raw 5xx HTML error pages (e.g. Nginx 502/504) into structured user-facing JSON messages.
