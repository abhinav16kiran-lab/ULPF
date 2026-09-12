# Universal Log Processing Framework (ULPF)
## High-Level System Architecture — Production Reference

---

## 1. Core Objective

ULPF (Universal Log Processing Framework) is an enterprise-grade log ingestion, AI-assisted field mapping, and forensic audit platform. It ingests heterogeneous multi-vendor log streams at high throughput (10,000+ EPS), preserves raw payload lineage, maps raw keys to canonical schemas, and provides cryptographic proof against post-ingestion log tampering.

---

## 2. Platform Architecture Overview

```text
                                [ Log Sources & Vendors ]
                     (CrowdStrike, Palo Alto, Microservices, Sensors)
                                           │
                                           │ HTTP POST /v1/events (X-API-Key)
                                           ▼
┌───────────────────────────────────────────────────────────────────────────────────────┐
│ ULPF REACT FRONTEND (Warm Kinetic UI / Nginx Reverse Proxy Port 3000)                │
└──────────────────────────────────────────┬────────────────────────────────────────────┘
                                           │
                                           ▼
┌───────────────────────────────────────────────────────────────────────────────────────┐
│ ULPF SPRING BOOT CORE ENGINE (Port 8080)                                               │
│                                                                                       │
│  ┌──────────────────────────────┐        ┌─────────────────────────────────────────┐  │
│  │ CONTROL PLANE                │        │ DATA PLANE (Zero-Overhead Ingestion)    │  │
│  │ • Onboarding Workflow        │        │ • Header-Based Protocol Autodetector    │  │
│  │ • AI Mapping Engine          │        │ • In-Memory Lock-Free Queue Buffer      │  │
│  │ • Schema Governance          │        │ • ClickHouse Async Micro-Batching       │  │
│  │ • User Authentication & JWT  │        │ • Out-of-Band Merkle Tree Hashing       │  │
│  └──────────────┬───────────────┘        └────────────────────┬────────────────────┘  │
└─────────────────┼─────────────────────────────────────────────┼───────────────────────┘
                  │                                             │
                  ▼                                             ▼
┌────────────────────────────────────────┐    ┌─────────────────────────────────────────┐
│ SQLITE CONTROL PLANE DB                │    │ CLICKHOUSE DATA PLANE DB                │
│ (data/control-plane.db)                │    │ (Port 8123 / 9000)                      │
│ • users, vendors, sources, credentials │    │ • ulpf_raw.raw_events                   │
│ • mapping_versions, onboarding_requests│    │ • ulpf_events.canonical_events          │
│ • batch_integrity_blocks (Merkle Roots)│    │ • ulpf_events.events_{vendor}_{source}  │
└────────────────────────────────────────┘    └─────────────────────────────────────────┘
```

---

## 3. Core Architectural Modules

### 3.1 Control Plane
* **User Authentication & Role Governance**: Enforces JWT Bearer token authentication supporting `ADMIN`, `VENDOR`, and `USER` roles.
* **Vendor & Log Source Management**: Manages vendor identities, log sources, and active API key credentials with microsecond RAM caching and 5-minute idle eviction.
* **Onboarding Workflow**: Accepts sample log payloads and schema files, runs AI field discovery, and queues candidate mappings for admin review.

### 3.2 AI Mapping Engine (Layers 1 – 4)
* **Layer 1 (Exact Match)**: Instant SQLite dictionary lookup.
* **Layer 2 (TF-IDF Similarity)**: N-gram term frequency-inverse document frequency cosine matching.
* **Layer 3 (Typo Tolerance)**: Levenshtein edit distance matching.
* **Layer 4 (Vector Embeddings)**: Local ONNX `all-MiniLM-L6-v2` 384-dimensional vector cosine similarity.
* **Human Feedback Learning Loop**: Captures manual admin adjustments to continuously refine candidate confidence scores.

### 3.3 Data Plane (Zero-Overhead Ingestion)
* **Protocol Autodetector**: Automatically parses Syslog (RFC 3164/5424), CEF, LEEF, and JSON payloads.
* **Non-Blocking Micro-Batching**: Enqueues events into a `ConcurrentLinkedQueue` memory buffer. Triggers ClickHouse async inserts (`async_insert=1`) when batch size reaches 500 events or every 1,000 ms.
* **0% Ingestion Overhead**: Heavy operations (Merkle hashing, ClickHouse columnar merging, AI parsing) run asynchronously out-of-band, preserving sub-millisecond client HTTP response times.

### 3.4 Cryptographic Merkle Tree Forensic Audit Engine
* **Tamper-Evident Ledger**: Batches log payloads into cryptographic blocks, computes SHA-256 binary Merkle tree root hashes, and chains block hashes in SQLite (`batch_integrity_blocks`).
* **Live Forensic Auditor**: On-demand audit engine recalculates Merkle roots over raw ClickHouse logs and flags modified records as `TAMPERED_DETECTED`.

### 3.5 Dynamic ClickHouse Schema Provisioning
* **Dynamic Table Engine**: Automatically provisions isolated ClickHouse tables (`events_{vendor}_{source}`) upon onboarding approval.
* **Lossless Schema Overflow**: Unmapped raw vendor payload fields route into a `raw_unmapped` column, eliminating runtime `ALTER TABLE` locks while guaranteeing 100% zero data loss.

---

## 4. Technology Stack & Packaging

| Layer | Component | Technology |
| :--- | :--- | :--- |
| **Frontend** | UI SPA | React 18, Vite, Tailwind CSS, Warm Kinetic Design System |
| **Web Server / Proxy** | Edge Proxy | Nginx Alpine (HTML5 SPA routing + API reverse proxy) |
| **Backend Engine** | Core API & Logic | Java 21, Spring Boot 3.4, HikariCP, Spring Security, JWT |
| **Control Database** | Metadata Store | SQLite 3 (`data/control-plane.db`) |
| **Data Database** | Analytical Storage | ClickHouse 26.3 LTS (`ulpf_raw` & `ulpf_events`) |
| **AI Model Engine** | Vector Embeddings | ONNX Runtime Java, `all-MiniLM-L6-v2` (Local Zero-RAM Lifecycle) |
| **Orchestration** | Container Runtime | Podman / Docker Compose (`compose.yaml`, `start.sh`, `start.bat`) |
