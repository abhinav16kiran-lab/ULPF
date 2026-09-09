# Universal Log Processing Framework (ULPF)

[![Build & Test](https://github.com/abhinav16kiran-lab/ULPF/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/abhinav16kiran-lab/ULPF/actions)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21_LTS-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.0-green.svg)](https://spring.io/projects/spring-boot)
[![ClickHouse](https://img.shields.io/badge/ClickHouse-26.3_LTS-yellow.svg)](https://clickhouse.com/)
[![React](https://img.shields.io/badge/React-19.0-cyan.svg)](https://react.dev/)

**ULPF (Universal Log Processing Framework)** is an enterprise-grade, plug-and-play log ingestion, normalization, and analytical query platform designed to eliminate schema integration friction across multi-vendor infrastructure.

When vendors submit sample log payloads (JSON, Syslog RFC 3164/5424, ArcSight CEF, IBM QRadar LEEF), an AI-powered 4-layer mapping engine automatically analyzes payload structures and proposes semantic mappings into a canonical schema. A human administrator reviews, edits, and approves proposals via the **Admin Dashboard** before an API key is issued. 

Once onboarded, vendor logs flow into high-performance **ClickHouse columnar storage** at live ingestion (`POST /v1/events`) with **0% AI overhead** using high-speed deterministic header format autodetection. Raw logs are preserved losslessly, normalized in real-time, and cryptographically verified using **SHA-256 Merkle Tree batching** for tamper-evidence.

---

# SECTION 1: QUICK START & SETUP GUIDE

This section provides the fastest methods to get ULPF running on your local system or server.

---

## 1. Clone the Repository

Clone the project repository to your local machine:
```bash
git clone https://github.com/abhinav16kiran-lab/ULPF.git
cd ULPF
```

---

## 2. Prerequisites & Container Runtime Detection

Ensure you have one of the following container orchestrators installed:
* **Podman** & **Podman Compose** (`podman-compose` or `podman compose`)
* **Docker** & **Docker Compose** (`docker compose` or `docker-compose`)

*(If running services natively without containers, Java 21 LTS, Maven 3.9+, and Node.js 21+ are required).*

---

## 3. Fast Startup Methods

Choose the startup option that best fits your workflow:

### Option A: One-Command Automated Setup (Fastest & Recommended)

Run the automated platform launcher for your operating system:

* **Linux / macOS**:
  ```bash
  ./start.sh
  ```

* **Windows (Command Prompt / PowerShell)**:
  ```cmd
  start.bat
  ```

#### What `start.sh` & `start.bat` do automatically:
1. Creates required local storage directories (`core-engine/data`, `core-engine/storage`).
2. Checks for `.env`. If missing, automatically copies `.env.example` $\rightarrow$ `.env`.
3. **Interactive Credentials Setup**: Prompts you if you would like to customize Admin & ClickHouse credentials on first launch.
4. **Smart Container Runtime Auto-Detection**: Checks for container engines in optimal order:
   - `podman-compose`
   - `podman compose`
   - `docker compose`
   - `docker-compose`
   - *If neither Podman nor Docker is installed*, the script outputs a clear error message directing you to download Docker Desktop or Podman Desktop, and exits cleanly.
5. Launches all 3 containerized services (`ulpf-clickhouse`, `ulpf-core-engine`, `ulpf-frontend`).
6. Polls backend health endpoints and displays final service URLs once live.

---

### Option B: Standard Docker Compose (Manual Build)

If you prefer building and running containers manually:

```bash
# 1. Create .env from template
cp .env.example .env

# 2. Build and launch all container services
docker compose up --build -d

# (Or using Podman)
podman compose up --build -d
```

---

### Option C: Run Pre-Built Container Images from GitHub Container Registry (GHCR)

To run pre-packaged production container images published directly from GitHub Actions without building source code:

```bash
# Pull published images from GHCR
docker pull ghcr.io/abhinav16kiran-lab/ulpf/core-engine:latest
docker pull ghcr.io/abhinav16kiran-lab/ulpf/frontend:latest
docker pull ghcr.io/abhinav16kiran-lab/ulpf/clickhouse:latest

# Launch container stack
docker compose up -d
```

---

## 3. Platform Service Endpoints & Access

Once started, access the ULPF web applications and REST APIs:

| Interface / Service | URL / Access Point | Default Credentials | Description |
| :--- | :--- | :--- | :--- |
| **Frontend Web App** | [http://localhost:3000](http://localhost:3000) | `admin` / `Admin@12345` | Unified React App (Admin, Analytics, Onboarding) |
| **Admin Dashboard** | [http://localhost:3000/admin](http://localhost:3000/admin) | `admin` / `Admin@12345` | Proposal review, mapping edits, vendor approvals |
| **Analytics Console** | [http://localhost:3000/analytics](http://localhost:3000/analytics) | `admin` / `Admin@12345` | Visual query builder, SQL console, lineage lookup |
| **Vendor Onboarding** | [http://localhost:3000/onboard](http://localhost:3000/onboard) | Vendor account / Admin | Log source onboarding & sample payload submission |
| **Core Engine REST API** | [http://localhost:8080/v1](http://localhost:8080/v1) | JWT Bearer / `X-API-Key` | Backend Spring Boot API service |
| **Healthcheck Endpoint** | [http://localhost:8080/v1/health](http://localhost:8080/v1/health) | None | System status and database readiness check |
| **ClickHouse HTTP Interface**| [http://localhost:8123](http://localhost:8123) | `default` / `Clickhouse123!` | Columnar analytics database engine |

---

## 4. Centralized Environment Configuration (`.env`)

ULPF relies on `.env` to configure system credentials and database parameters:

```ini
# ==============================================================================
# ULPF Environment Configuration
# ==============================================================================

# ---- Admin Credentials (Used for Admin Dashboard & System Operations) ----
ULPF_ADMIN_USERNAME=admin
ULPF_ADMIN_PASSWORD=Admin@12345

# ---- Core Engine (Java 21 / Spring Boot) ----
CORE_ENGINE_PORT=8080
SPRING_PROFILES_ACTIVE=dev
SQLITE_DB_PATH=/app/data/control-plane.db
JWT_SECRET=super-secret-jwt-signing-key-for-ulpf-dev-12345
API_KEY_HASH_SALT=dev-salt-key-for-api-hash-12345

# ---- ClickHouse Database ----
CLICKHOUSE_HOST=clickhouse
CLICKHOUSE_HTTP_PORT=8123
CLICKHOUSE_NATIVE_PORT=9000
CLICKHOUSE_DB=ulpf_raw
CLICKHOUSE_USER=default
CLICKHOUSE_PASSWORD=Clickhouse123!
```

> **Automatic Admin User Seeding**: On startup, Spring Boot (`AdminUserSeeder`) checks SQLite for `ULPF_ADMIN_USERNAME`. If absent or modified in `.env`, it automatically seeds or updates the admin account in SQLite with BCrypt hashed credentials.

---

## 5. End-to-End Walkthrough (Using the Platform)

### Step 1: Log in as Administrator
1. Open [http://localhost:3000/login](http://localhost:3000/login).
2. Login with `admin` / `Admin@12345`.
3. You will be redirected to the **Admin Dashboard** (`/admin`).

### Step 2: Onboard a Log Source (`/onboard`)
1. Click **Onboarding** in the top navigation bar.
2. Enter Vendor Name (e.g. `CyberGuard`), Source Name (e.g. `FW-Gateway`), and Source Type (`FIREWALL`).
3. Paste a sample log snippet (JSON, Syslog `<134>1...`, ArcSight `CEF:0|...`, or IBM `LEEF:2.0|...`).
4. Click **Submit Onboarding Request**.

### Step 3: Review & Approve Mappings (`/admin`)
1. Go to **Admin Dashboard** (`/admin`).
2. Select the pending onboarding request.
3. Inspect the AI proposed field mappings and confidence scores.
4. Click **Approve Request**. The engine issues a production API Key (`ulpf_live_...`) and activates the mapping version.

### Step 4: Live Log Ingestion (`POST /v1/events`)
Ingest logs with 0% AI overhead using `curl`:
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
  "detectedFormat": "JSON",
  "traceId": "tr_66b912a"
}
```

### Step 5: Query & Analyze (`/analytics`)
1. Open **Analytics Console** (`/analytics`).
2. Run custom ClickHouse SQL queries or use the visual Query Builder to aggregate logs by vendor, source IP, or event action.

---

# SECTION 2: TECHNICAL ARCHITECTURE & CORE SPECIFICATIONS

---

## 1. High-Performance Architecture

ULPF maintains a strict separation between the **Control Plane** (AI onboarding, schema mapping management, user auth, notifications) and the **Data Plane** (deterministic log ingestion, header format autodetection, real-time normalization, ClickHouse batching).

```text
                               ┌─────────────────────────────────────────┐
                               │       Vendor Log Source / Sender        │
                               └────────────────────┬────────────────────┘
                                                    │
                                                    │ HTTP POST /v1/events
                                                    │ Header: X-API-Key
                                                    ▼
 ┌──────────────────────────────────────────────────────────────────────────────────────────────────────────┐
 │                                      CORE ENGINE DATA PLANE                                              │
 │                                                                                                          │
 │   ┌───────────────────────────┐    ┌───────────────────────────┐    ┌────────────────────────────────┐   │
 │   │  ApiKey Auth & Validation │ ──►│ Header Format Autodetector│ ──►│ Thread-Safe Queue Buffer       │   │
 │   │  (RAM SHA-256 Lookup)     │    │ (Syslog, CEF, LEEF, JSON) │    │ (ConcurrentLinkedQueue)        │   │
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

## 2. Header-Based Format Autodetector & Parser Engine

Live log ingestion (`POST /v1/events`) uses constant-time $O(1)$ header inspection before parsing:
- `<PRI>` $\rightarrow$ **Syslog (RFC 3164 / RFC 5424)**: Extracts priority, facility, severity, hostname, app name, structured data, and msg payload.
- `CEF:0|` $\rightarrow$ **ArcSight CEF**: Extracts CEF version, device vendor/product, severity, and key-value extensions.
- `LEEF:2.0|` $\rightarrow$ **IBM QRadar LEEF**: Extracts LEEF version, vendor, product, event ID, and delimiter-aware extension key-values.
- `{ ... }` $\rightarrow$ **JSON / JSON Array**: Fast Jackson parser object mapping.

---

## 3. 4-Layer AI Mapping Engine (Control Plane Only)

AI mapping takes place **exclusively during vendor onboarding** (`/v1/onboard`):
1. **Layer 1: Dictionary & Exact Matcher**: Checks canonical dictionary aliases (`mapping_aliases`).
2. **Layer 2: TF-IDF & Character N-Gram Matcher**: Calculates string similarity over canonical fields.
3. **Layer 3: Levenshtein Typo Matcher**: Evaluates edit distances for key names.
4. **Layer 4: Local ONNX Vector Embedding**: Employs an embedded `all-MiniLM-L6-v2` ONNX model to compute semantic distance vectors for unknown fields.

---

## 4. Cryptographic Merkle Tree Batching

Log batches are hashed into SHA-256 binary Merkle Trees (`BatchIntegrityService`):
- Merkle roots are computed for every batch flush.
- Batch integrity blocks record `merkle_root`, `previous_block_hash`, and event range.
- Tamper audits recalculate Merkle roots on demand via `/v1/integrity/verify/{id}` to detect data corruption or unauthorized SQL edits.

---

## 5. Technology Stack

| Layer | Technology | Details |
| :--- | :--- | :--- |
| **Backend Engine** | Java 21 LTS + Spring Boot 4.1.0 | Microsecond Data Plane, Jackson, HikariCP |
| **Control DB** | SQLite 3 | Embedded control store (`sqlite-jdbc` 3.49) |
| **Analytics Engine**| ClickHouse Server 26.3 | High-throughput columnar storage & async insert |
| **AI Model** | ONNX Runtime | Local `all-MiniLM-L6-v2` embedding model |
| **Frontend UI** | React 19 + Vite 6 | Modern design system, Lucide icons, Tailwind CSS |
| **CI/CD & Containers**| Docker / Podman + GitHub Actions | Automated build, unit tests (142/142 passing), GHCR publish |

---

## 6. Complete REST API Reference

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/v1/auth/login` | None | Authenticate user & receive JWT token |
| `POST` | `/v1/auth/register` | None | Register user account |
| `POST` | `/v1/onboard` | Bearer Token | Submit vendor log source onboarding request |
| `POST` | `/v1/onboard/update` | Bearer Token | Submit schema update for existing log source |
| `GET` | `/v1/admin/onboard` | Admin Token | List pending onboarding requests |
| `POST` | `/v1/admin/onboard/{id}/approve` | Admin Token | Approve request & issue API key |
| `POST` | `/v1/admin/onboard/{id}/reject` | Admin Token | Reject onboarding request |
| `GET` | `/v1/analytics` | Admin Token | Run ClickHouse analytical queries |
| `GET` | `/v1/analytics/lineage/{id}` | Admin Token | Trace raw input fields for aggregated metric |
| `GET` | `/v1/integrity/blocks` | Bearer Token | List Merkle batch integrity blocks |
| `POST` | `/v1/integrity/verify/{id}` | Bearer Token | Execute cryptographic forensic audit |
| `POST` | `/v1/events` | `X-API-Key` | Runtime log ingestion (Syslog, CEF, LEEF, JSON) |
| `GET` | `/v1/health` | None | Health check & service readiness |

---

## 7. License & Authors

This project is licensed under the [Apache License, Version 2.0](LICENSE).  
See [AUTHORS](AUTHORS) for full author details.
