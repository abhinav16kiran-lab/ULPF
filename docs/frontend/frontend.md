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
| `/login` | `LoginPage.jsx` | Public | Authentication & JWT token acquisition |
| `/signup` | `SignupPage.jsx` | Public | User registration (`ADMIN`, `VENDOR`, `USER`) |
| `/onboard` | `OnboardingPage.jsx` | `VENDOR`, `ADMIN` | 50/50 split workspace for log sample upload & live payload parsing |
| `/admin` | `AdminDashboardPage.jsx` | `ADMIN` | Schema governance, AI mapping review & one-click ClickHouse activation |
| `/notifications` | `NotificationsPage.jsx` | Authenticated | User notification feed, unread toggles & API key delivery |
| `/integrity` | `IntegrityConsolePage.jsx` | `ADMIN`, `VENDOR`, `USER` | Cryptographic Merkle tree forensic audit console |
| `/analytics` | `AnalyticsPage.jsx` | `ADMIN` | ClickHouse query builder (Builder Mode, Direct SQL, Lineage, Grafana) |

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
* Axios API requests automatically attach `Authorization: Bearer <token>` headers via `src/api/client.js`.
