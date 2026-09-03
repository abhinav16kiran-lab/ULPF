# Frontend Architecture & Implementation Specification (`frontend`)

This document describes the complete architecture, page-by-page API integrations, client routing, containerization setup, and production deployment using Nginx for the ULPF React Frontend.

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
     - Serves React SPA bundles                      - Proxies /v1/* endpoints
     - Handles HTML5 pushState fallback              - Forwarded to http://core-engine:8080/v1/
     - Location / -> index.html                      - Strips CORS overhead in production
```

### Containerization Files

#### 1. `frontend/Containerfile`
* **Stage 1 (`builder`):** Uses `node:21-alpine`. Executes `npm ci` and `npm run build` (Vite) to output optimized static HTML/JS/CSS assets to `/app/dist`.
* **Stage 2 (`runner`):** Uses `nginx:alpine`. Copies built assets to `/usr/share/nginx/html` and installs custom `nginx.conf`. Exposes port 80.

#### 2. `frontend/nginx.conf`
```nginx
server {
    listen 80;
    server_name localhost;

    root /usr/share/nginx/html;
    index index.html;

    # SPA Client Routing: Fallback to index.html for React Router pushState routes
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Reverse Proxy /v1/ API requests directly to Core Engine container
    location /v1/ {
        proxy_pass http://core-engine:8080/v1/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

#### 3. Root `compose.yaml` Orchestration
The frontend container service is fully integrated into the root `compose.yaml` orchestrator:
```yaml
  frontend:
    build:
      context: ./frontend
      dockerfile: Containerfile
    container_name: ulpf-frontend
    ports:
      - "3000:80"
    depends_on:
      - core-engine
    networks:
      - ulpf-net
```

---

## 2. Page-by-Page API Integration Plan

### 1. `LoginPage.jsx`

**State:**
- `username` (string), `password` (string), `role` (string, default `"ADMIN"`)
- `error` (string | null) — holds the error message to display
- `loading` (boolean) — disables the submit button and shows "Logging in…"

**API call:**
- `POST /v1/login` via `client.post("/v1/login", { username, password, role })`

**On success (200):**
- Store `token`, `username`, and `role` from the response body into `localStorage`
- Navigate to `/admin` if `role === "ADMIN"`, otherwise navigate to `/onboard`

**On error (400/401):**
- Response body may be plain text or JSON. Extract the message defensively:
  - If `error.response.data` is a string → display it directly
  - If it's an object → display `error.response.data.error` or `JSON.stringify(error.response.data)`
  - Fallback: `error.message`

---

### 2. `SignupPage.jsx`

**State:**
- `name`, `username`, `password`, `confirmPassword` (all strings)
- `error` (string | null)
- `loading` (boolean)

**Client-side validation:**
- Before submitting, check `password === confirmPassword`. If not, set `error` to `"Passwords do not match"` and abort the request.

**API call:**
- `POST /v1/signup` via `client.post("/v1/signup", { name, username, password, confirmPassword })`

**On success (200):**
- Navigate to `/login` (user must log in after signup)

**On error (400):**
- Response is plain text. Display `error.response.data` (string) or fallback to `error.message`.

---

### 3. `OnboardingPage.jsx`

**State:**
- `vendorName`, `sourceName`, `sourceType` (strings, required)
- `sampleLogFile`, `schemaFile` (File | null, optional)
- `result` (object | null) — holds `{ requestId, status, message }` on success
- `error` (string | null)
- `loading` (boolean)

**Pre-check:**
- Read `username` from `localStorage`. If missing, navigate to `/login`.

**API call:**
- Build a `FormData` object with the text fields and optional files
- `POST /v1/onboard/{username}` via `client.post("/v1/onboard/" + username, formData)`
- The axios client's interceptor automatically attaches the Bearer token
- Do NOT manually set `Content-Type` — let axios/browser set the multipart boundary automatically

**On success (201):**
- Display the returned `requestId` and `status` from the response body

**On error (400/403):**
- Response is JSON `{ "error": string }`. Display `error.response.data.error` or fallback.

---

### 4. `AdminDashboardPage.jsx`

**State:**
- `requests` (array) — the list of onboarding requests
- `loading` (boolean) — for the initial fetch
- `error` (string | null) — for the initial fetch
- `actionLoading` (string | null) — holds the `requestId` currently being acted on (to disable its buttons)
- `actionError` (string | null) — error from a decision action

**On mount:**
- `GET /v1/admin/onboard` via `client.get("/v1/admin/onboard")`
- Store `response.data.requests` in state

**Rendering:**
- Each request rendered as a list item showing: `requestId`, `submittedBy`, `vendorName`, `sourceName`, `sourceType`, `status`, `createdAt`
- If `status === "SUBMITTED"`, show **Approve** and **Reject** buttons

**Approve/Reject:**
- `PUT /v1/admin/onboard/{requestId}` via `client.put("/v1/admin/onboard/" + requestId, { decision: "APPROVED" | "REJECTED" })`
- On success: re-fetch the full list (simple approach — avoids stale-state bugs)
- On error: display `error.response.data.error` or fallback

---

### 5. `NotificationsPage.jsx`

**State:**
- `notifications` (array)
- `loading` (boolean)
- `error` (string | null)

**On mount:**
- `GET /v1/notifications` via `client.get("/v1/notifications")`
- Store `response.data.notifications` in state

**Rendering:**
- If array is empty → show "No notifications"
- Otherwise, render each item as `<li>{JSON.stringify(item)}</li>`

---

### 6. `AnalyticsPage.jsx`

**State:**
- `table`, `column` (strings)
- `aggregation` (string, default `"COUNT"`)
- `result` (object | null) — holds `{ table, column, aggregation, result }` on success
- `error` (string | null)
- `loading` (boolean)

**API call:**
- `GET /v1/analytics?table=...&column=...&aggregation=...` via `client.get("/v1/analytics", { params: { table, column, aggregation } })`

**On success (200):**
- Display the `result` value along with the query parameters

---

### 7. Logout Component

**Placement:** Rendered inside protected pages (`src/components/LogoutButton.jsx`).
**Approach:** Clears `localStorage` keys (`"token"`, `"username"`, `"role"`) and navigates to `/login`.

---

## 3. Verified File Inventory

| File | Purpose |
| :--- | :--- |
| `frontend/Containerfile` | Multi-stage build (Node.js 21 builder $\rightarrow$ Nginx Alpine runner) |
| `frontend/nginx.conf` | Edge web server config (SPA routing fallback + `/v1/` reverse proxy) |
| `frontend/.dockerignore` | Context exclusions (`node_modules`, `dist`, `.git`) |
| `frontend/src/api/client.js` | Axios HTTP instance with JWT Bearer header interceptor |
| `frontend/src/components/LogoutButton.jsx` | Shared logout component clearing user session |
| `frontend/src/pages/LoginPage.jsx` | Authentication page (`POST /v1/login`) |
| `frontend/src/pages/SignupPage.jsx` | User registration page (`POST /v1/signup`) |
| `frontend/src/pages/OnboardingPage.jsx` | Vendor source onboarding form (`POST /v1/onboard/{username}`) |
| `frontend/src/pages/AdminDashboardPage.jsx` | Admin review interface (`GET/PUT /v1/admin/onboard`) |
| `frontend/src/pages/NotificationsPage.jsx` | User notifications page (`GET /v1/notifications`) |
| `frontend/src/pages/AnalyticsPage.jsx` | Log analytics querying page (`GET /v1/analytics`) |
