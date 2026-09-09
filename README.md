# Universal Log Processing Framework (ULPF)

[![Build & Test](https://github.com/abhinav16kiran-lab/ULPF/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/abhinav16kiran-lab/ULPF/actions)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21_LTS-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.0-green.svg)](https://spring.io/projects/spring-boot)
[![ClickHouse](https://img.shields.io/badge/ClickHouse-26.3_LTS-yellow.svg)](https://clickhouse.com/)
[![React](https://img.shields.io/badge/React-19.0-cyan.svg)](https://react.dev/)

**ULPF (Universal Log Processing Framework)** is an enterprise-grade, plug-and-play log ingestion, normalization, and analytical query platform. It eliminates manual schema integration friction across multi-vendor cloud environments. 

When vendors submit sample log payloads, an AI-powered 4-layer mapping engine automatically analyzes payload structures and proposes semantic mappings into a canonical schema. A human administrator reviews, edits, and approves proposals via the **Admin Dashboard** before an API key is issued. Once onboarded, vendor logs flow into high-performance **ClickHouse columnar storage**, where raw logs are preserved losslessly, normalized in real-time, and cryptographically verified using **SHA-256 Merkle Tree batching** for tamper-evidence.

---

# SECTION 1: SETUP & DEVELOPER QUICK START GUIDE

This section contains everything you need to configure, build, run, and test ULPF on your local machine or server.

---

## 1. Prerequisites

Ensure you have the following installed on your host system:
* **Docker** (20.10+) & **Docker Compose** OR **Podman** & **Podman Compose**
* **Java 21 LTS** & **Maven 3.9+** (if building backend locally without containers)
* **Node.js 21 LTS** & **npm** (if running React frontend locally without containers)

---

## 2. Environment Configuration (`.env`)

ULPF uses a centralized `.env` file to manage database credentials, JWT secrets, application ports, and administrator login credentials.

### Step 2.1: Create Your `.env` File
Run the following command in the project root directory:
```bash
cp .env.example .env
```

### Step 2.2: Review Environment Variables

The default `.env` file comes pre-configured with secure development defaults:

```ini
# ==============================================================================
# ULPF (Universal Log Processing Framework) Environment Configuration
# ==============================================================================

# ---- General ----
ENV=development

# ---- Admin Credentials (Used for Admin Dashboard & System Operations) ----
ULPF_ADMIN_USERNAME=admin
ULPF_ADMIN_PASSWORD=Admin@12345

# ---- Core Engine (Java 21 / Spring Boot) ----
CORE_ENGINE_PORT=8080
SPRING_PROFILES_ACTIVE=dev
SQLITE_DB_PATH=./data/control-plane.db
JWT_SECRET=super-secret-jwt-signing-key-for-ulpf-dev-12345
API_KEY_HASH_SALT=dev-salt-key-for-api-hash-12345

# ---- ClickHouse Storage Engine ----
CLICKHOUSE_HOST=localhost
CLICKHOUSE_HTTP_PORT=8123
CLICKHOUSE_NATIVE_PORT=9000
CLICKHOUSE_DB=ulpf_raw
CLICKHOUSE_USER=default
CLICKHOUSE_PASSWORD=Clickhouse123!

# ---- Frontend ----
VITE_CORE_ENGINE_API_URL=http://localhost:8080
```

### Default Credentials Summary

| Service / Interface | Username / Identifier | Password | Environment Variable |
| :--- | :--- | :--- | :--- |
| **Admin Dashboard UI** (`/admin`) | `admin` | `Admin@12345` | `ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD` |
| **Analytics Console UI** (`/analytics`) | `admin` | `Admin@12345` | `ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD` |
| **ClickHouse Database** | `default` | `Clickhouse123!` | `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` |

> **Note on Automatic Admin Seeding**: On application startup, the Core Engine (`AdminUserSeeder`) checks SQLite for the configured `ULPF_ADMIN_USERNAME`. If missing or outdated, it automatically seeds/updates the account with `Role.ADMIN` and BCrypt password hash matching `.env`.

---

## 3. Quick Start (Podman / Docker Compose)

The easiest way to start the complete ULPF platform (ClickHouse + Core Engine + React Frontend) is using container compose:

### Step 3.1: Launch All Containers
```bash
# Using Podman Compose:
podman-compose up --build

# OR using Docker Compose:
docker compose up --build
```

### Step 3.2: Verify Container Health
Once started, the following services will be live:
* **React Web Application**: [http://localhost:3000](http://localhost:3000)
* **Core Engine REST API**: [http://localhost:8080/v1/health](http://localhost:8080/v1/health)
* **ClickHouse HTTP Interface**: [http://localhost:8123/ping](http://localhost:8123/ping)

To shut down all services:
```bash
podman-compose down -v
```

---

## 4. Running Services Individually (Local Development)

If you prefer to run services natively for active code development:

### 1. Start ClickHouse Engine Only
```bash
podman-compose up clickhouse
```

### 2. Start Core Engine (Spring Boot Backend)
```bash
cd core-engine
mvn clean spring-boot:run
```
*(Spring Boot automatically initializes SQLite tables on startup from `classpath:sqlite/schema.sql` and seeds the admin user).*

### 3. Start Frontend (React Dev Server)
```bash
cd frontend
npm install
npm run dev
```
Open [http://localhost:5173](http://localhost:5173) in your browser.

---

## 5. End-to-End System Walkthrough (How to Use ULPF)

Follow this step-by-step walkthrough to test the complete lifecycle:

### Step 1: Login to the Web Console
1. Navigate to `http://localhost:3000/login`.
2. Select **Administrator** role and enter:
   - **Username**: `admin`
   - **Password**: `Admin@12345`
3. Click **Log In**. You will be authenticated and redirected to the **Admin Dashboard**.

### Step 2: Vendor Source Onboarding (`/onboard`)
1. Click **Onboarding** in the top navigation bar.
2. Choose **New Source Onboarding** or **Update Existing Source**.
3. Enter Vendor Name (e.g. `Acme Corp`), Source Name (e.g. `Firewall-East`), and Source Type (`FIREWALL`).
4. Paste a sample log payload or upload a sample JSON log file.
5. Click **Submit Onboarding Request**.

### Step 3: Admin Review & Proposal Editing (`/admin`)
1. Navigate to the **Admin Dashboard** (`/admin`).
2. Select the submitted onboarding request from the queue.
3. Review the AI-generated candidate mapping proposal (confidence scores, field mappings).
4. Edit candidate mapping JSON if needed or inspect the **Version Diff View** for schema updates.
5. Click **Approve Request**. The system generates a raw ingestion API key (`ulpf_live_...`), activates the mapping version, and sends a notification.

### Step 4: Runtime Log Ingestion (`POST /v1/events`)
Simulate runtime log ingestion using `curl` with your generated API key:
```bash
curl -X POST http://localhost:8080/v1/events \
  -H "X-API-Key: ulpf_live_YOUR_GENERATED_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "src_ip": "192.168.1.50",
    "dest_ip": "10.0.0.12",
    "status_code": 200,
    "action": "ALLOW",
    "bytes_sent": 1420
  }'
```
*Response (`202 Accepted`)*:
```json
{
  "eventId": "evt_984a12_01",
  "status": "ACCEPTED",
  "traceId": "tr_66b912a"
}
```

### Step 5: Real-Time ClickHouse Analytics Console (`/analytics`)
1. Navigate to **Analytics Console** (`/analytics`).
2. **Builder Mode**: Select Table (`logs_canonical`), Column (`source_ip`), Aggregation (`COUNT`), and Group By (`status_code`). Click **Run Analytics Query**.
3. **Direct SQL Mode**: Write custom read-only ClickHouse queries.
4. **Lineage Lookup Mode**: Enter Lineage ID (`ling_984a12`) to trace raw contributing readings for aggregate telemetry events.

### Step 6: Cryptographic Merkle Tamper Audit (`/integrity`)
1. Click **🔐 Integrity Audit** in the top header.
2. View batch integrity blocks calculated over ingested logs.
3. Click **Run Forensic Audit** to verify SHA-256 Merkle Roots and block chain continuity.

---

# SECTION 2: TECHNICAL ARCHITECTURE & DEEP SPECIFICATIONS

---

## 1. System Architecture Overview

ULPF enforces a strict separation between the **Control Plane** (account management, schema proposals, mapping state, vendor notification) and the **Data Plane** (high-throughput log processing, raw preservation, normalization, and analytical storage).

```text
                               ┌─────────────────────────────────────────┐
                               │       Vendor Application / Gateway      │
                               └────────────────────┬────────────────────┘
                                                    │
                                                    │ HTTP POST /v1/events
                                                    │ Header: X-API-Key
                                                    ▼
 ┌──────────────────────────────────────────────────────────────────────────────────────────────────────────┐
 │                                      CORE ENGINE DATA PLANE                                              │
 │                                                                                                          │
 │   ┌───────────────────────────┐    ┌───────────────────────────┐    ┌────────────────────────────────┐   │
 │   │  TracingFilter & Context  │ ──►│ Microsecond RAM Cache     │ ──►│ Thread-Safe Queue Buffer       │   │
 │   │  (TraceId / W3C Header)   │    │ (API Key / Mapping Version)│    │ (ConcurrentLinkedQueue)        │   │
 │   └───────────────────────────┘    └───────────────────────────┘    └───────────────┬────────────────┘   │
 └─────────────────────────────────────────────────────────────────────────────────────┼────────────────────┘
                                                                                       │
                                                   ┌───────────────────────────────────┴────────────────┐
                                                   │ Batch Flush (500 events / 1s timer / @PreDestroy)   │
                                                   ▼                                                    ▼
                                       ┌───────────────────────┐                            ┌──────────────────────┐
                                       │ ClickHouse Columnar DB│                            │  SQLite Control DB   │
                                       │ (ulpf_raw.raw_events) │                            │  (control-plane.db)  │
                                       └───────────────────────┘                            └──────────────────────┘
```

---

## 2. 4-Layer AI Mapping Engine

The AI Mapping Engine transforms arbitrary vendor log keys into canonical schema fields using a 4-layer cascade:

1. **Layer 1: Exact & Alias Dictionary Match**: Checks exact field name matches and learned alias dictionaries (`mapping_aliases`).
2. **Layer 2: TF-IDF & Character N-Gram Similarity**: Calculates n-gram token similarity over historical mapping corpus (`TfidfMatchingService`).
3. **Layer 3: Levenshtein Typo Matcher**: Evaluates edit distance against canonical schema fields (`TypoMatchingService`).
4. **Layer 4: Local ONNX Vector Embedding Model**: Uses local `all-MiniLM-L6-v2` transformer model via ONNX Runtime to compute cosine semantic distance for non-obvious fields without external API dependencies.

---

## 3. Cryptographic Merkle Tree Batching & Audit

Log events are batched into Merkle Trees (`BatchIntegrityService`):
- Each log payload hash forms a leaf node.
- A binary SHA-256 Merkle tree is computed to produce a single **Merkle Root**.
- Each batch block records `merkle_root`, `previous_block_hash`, `first_event_id`, and `last_event_id` in `batch_integrity_blocks`.
- Forensic audits recalculate the Merkle root on demand to detect any payload tampering or data corruption.

---

## 4. ClickHouse Schema Drift & Auto-Correction Engine

When an onboarded log source sends unexpected new fields at runtime:
1. Raw logs are losslessly preserved in ClickHouse `raw_events` (`JSON` column).
2. The drift detector (`SchemaDriftNotificationService`) extracts new keys and checks if they exist in the active mapping.
3. If new fields are detected, a schema drift notification is sent to the vendor and logged in `notifications`.
4. Vendors can visit `/onboard`, select **Update Existing Source**, and submit an updated schema version without breaking production ingestion or invalidating existing API keys.

---

## 5. Technology Stack

| Layer | Technology | Version / Details |
| :--- | :--- | :--- |
| **Core Engine** | Java LTS + Spring Boot | Java 21, Spring Boot 4.1.0, Maven 3.9+ |
| **Control-Plane DB** | SQLite 3 | `sqlite-jdbc` 3.49.1.0 + HikariCP connection pool |
| **Analytical Storage** | ClickHouse Server | ClickHouse 26.3 LTS + `clickhouse-jdbc` 0.7.2 |
| **AI Mapping Engine** | ONNX Runtime | `all-MiniLM-L6-v2` transformer model |
| **Frontend UI** | React + Vite | React 19, Vite 6, Tailwind CSS v3 |
| **Containerization** | Docker / Podman | Multi-stage Containerfiles, Nginx Alpine runner |
| **CI/CD Pipeline** | GitHub Actions | Automated build, test suite, and container publish |

---

## 6. Complete REST API Specification

| Method | Endpoint | Auth Required | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/v1/auth/login` | None | Authenticate user and issue JWT token |
| `POST` | `/v1/auth/register` | None | Register new user account |
| `POST` | `/v1/onboard` | Bearer Token | Submit new vendor log source onboarding request |
| `POST` | `/v1/onboard/update` | Bearer Token | Submit schema update for existing log source |
| `GET` | `/v1/admin/onboard` | Admin Token | List pending onboarding and schema update requests |
| `POST` | `/v1/admin/onboard/{id}/approve` | Admin Token | Approve request & activate proposed mapping version |
| `POST` | `/v1/admin/onboard/{id}/reject` | Admin Token | Reject request with vendor feedback notes |
| `GET` | `/v1/notifications` | Bearer Token | Fetch notifications for logged-in user |
| `POST` | `/v1/notifications/{id}/read` | Bearer Token | Mark notification as read |
| `GET` | `/v1/analytics` | Admin Token | Execute read-only ClickHouse aggregation query |
| `GET` | `/v1/analytics/lineage/{id}` | Admin Token | Trace raw contributing readings for aggregate event |
| `GET` | `/v1/integrity/blocks` | Bearer Token | List Merkle batch integrity blocks |
| `POST` | `/v1/integrity/verify/{id}` | Bearer Token | Run forensic Merkle root tamper audit |
| `POST` | `/v1/events` | `X-API-Key` | Runtime log ingestion endpoint |
| `GET` | `/v1/health` | None | Service health check |

---

## 7. License & Authors

This project is licensed under the [Apache License, Version 2.0](LICENSE).  
See [AUTHORS](AUTHORS) for the full list of project contributors.
