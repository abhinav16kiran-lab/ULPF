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
│  │ • Replay & Backfill Worker   │        │ • ClickHouse Async Micro-Batching       │  │
│  │ • Decoupled AlertDispatcher  │        │ • Out-of-Band Merkle Tree Hashing       │  │
│  │ • Vendor Webhook Governance  │        │ • Schema Drift & Failure Governance     │  │
│  └──────────────┬───────────────┘        └────────────────────┬────────────────────┘  │
└─────────────────┼─────────────────────────────────────────────┼───────────────────────┘
                  │                                             │
                  ▼                                             ▼
┌────────────────────────────────────────┐    ┌─────────────────────────────────────────┐
│ SQLITE CONTROL PLANE DB                │    │ CLICKHOUSE DATA PLANE DB                │
│ (data/control-plane.db)                │    │ (Port 8123 / 9000)                      │
│ • users, vendors, sources, credentials │    │ • ulpf_raw.raw_events                   │
│ • mapping_versions, onboarding_requests│    │ • ulpf_events.canonical_events          │
│ • vendor_webhooks & delivery_logs      │    │ • ulpf_events.events_{vendor}_{source}  │
│ • ingestion_alert_incidents            │    │                                         │
│ • batch_integrity_blocks (Merkle Roots)│    │                                         │
└────────────────────────────────────────┘    └─────────────────────────────────────────┘
```

---

## 3. Core Architectural Modules

### 3.1 Control Plane
* **User Authentication & Role Governance**: Enforces JWT Bearer token authentication supporting `ADMIN`, `VENDOR`, and `USER` roles. `JwtFilter` checks every token against the live account (cached 60 s, `AccountLookup`): role and super-admin status come from SQLite, and a password change (`pwv` fingerprint claim) or revoke ends sessions on the next request.
* **Admin Accounts**: No account is created from `.env`. The first visit to `/setup` asks for the one-time setup credentials (`ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD`) and creates the single **super-admin** (`users.is_super_admin`, unique index). The super-admin invites regular admins and resets their passwords with single-use 12 h links (`admin_links`, SHA-256 hashed), and revokes them (account deleted). A lost super-admin password is reset only on the server (`startup/reset-super-admin.sh`, `ResetSuperAdminCommand`). Admin passwords are 12-100 characters. Onboarding is closed to admins, so an admin can never become a vendor. Why each choice was made: §3.9.
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
* **Full-Record Traceability Hashing**: The Merkle tree leaf node for each log record is a deterministic hash of its full immutable identity (`event_id` + `vendor_id` + `source_id` + `lineage_id` + `raw_payload`), guaranteeing that both the content and origin metadata cannot be forged.
* **Bulk Forensic Auditor**: A high-speed, parallelized server-side audit engine recalculates Merkle roots over raw ClickHouse logs. Upon detecting a corrupted block, it returns exact deterministic traceability parameters (database table bounds) for root cause analysis.

### 3.5 Dynamic ClickHouse Schema Provisioning
* **Dynamic Table Engine**: Automatically provisions isolated ClickHouse tables (`events_{vendor}_{source}`) upon onboarding approval.
* **Lossless Schema Overflow**: Unmapped raw vendor payload fields route into a `raw_unmapped` column, eliminating runtime `ALTER TABLE` locks while guaranteeing 100% zero data loss.

### 3.6 Asynchronous Historical Replay & Backfill Engine

![Replay Backfill Pipeline Architecture](../../Architecture_Diagram/Replay%20Backfill%20Pipeline%20Architecture.png)

* **Automated & On-Demand Execution**: Automatically triggers when an onboarding request is approved in `OnboardingService`, or runs on-demand via Admin REST API (`POST /v1/admin/sources/{sourceId}/replay`).
* **Non-Blocking Background Worker**: Runs asynchronously via dedicated `CompletableFuture` thread pool with thread-safe cancellation tokens and in-memory job state tracking (`ReplayJobStatus`).
* **Bounded Cursor Streaming ($O(1)$ Memory)**: Reads raw historical logs from `ulpf_raw.raw_events` using composite primary index cursor pagination `(received_at, event_id)` in configurable chunks (default: 1,000), preventing JVM heap exhaustion regardless of historical dataset size.
* **Dynamic Schema Verification**: Automatically verifies the destination dynamic table exists prior to streaming, invoking `DynamicSchemaProvisioningService` to provision or migrate columns if needed.
* **Shared Mapping Transformation**: Reuses `MappingTransformer` to translate raw payloads into canonical schemas, extract `raw_unmapped` overflow JSON, and compute sensor metrics without duplicating transformation logic.
* **Adaptive ClickHouse Backpressure**: Proactively samples ClickHouse `MemoryTracking` metrics before streaming chunks; if memory utilization exceeds 80%, the worker throttles execution to protect database stability.
* **Safe Idempotency & Deduplication**: Performs per-chunk primary key membership checks against the target table before batch insertion, ensuring safe resumes after interruptions without generating duplicate canonical records.
* **Operational Notifications**: Dispatches role-tailored notifications to source owners and administrators upon job completion, cancellation, or failure.

### 3.7 Table Ingestion Failure & Schema Drift Governance
* **Sliding-Window Throttling & Incident Management**: A dedicated `TableIngestionFailureNotificationService` throttles failure alerts with a configurable 30-minute quiet window per `(source_id, failure_type)`. Prevents inbox/webhook flooding while aggregating dropped event counts into persistent incidents (`ingestion_alert_incidents`).
* **Ingestion-Path Drop Detection**: Monitored directly in `ClickHouseIngestionRepository.flushCanonical()`. Automatically detects unprovisioned target tables, missing schema definitions, and batch insert errors without dropping raw data from `ulpf_raw.raw_events`.
* **Zero-Overhead Schema Drift Detection**: `SchemaDriftNotificationService` profiles active ingestion batches for schema drift:
  - *High Drift Alert*: Triggered when $>30\%$ of extracted keys are diverted to `raw_unmapped`.
  - *Complete Drift Alert*: Triggered when $100\%$ of keys fail mapping, generating actionable instructions to trigger historical backfills (`POST /v1/admin/sources/{sourceId}/replay`).
* **Dynamic Column Reconciliation**: Invokes `DynamicSchemaProvisioningService.addColumnIfNotExists()` to add newly approved canonical fields to existing MergeTree tables on the fly without database recreation.

### 3.8 Tenant-Scoped Vendor Webhooks & Multi-Channel Alerting Pipeline
* **Asynchronous Multi-Channel Dispatcher**: `AlertDispatcher` routes operational alerts through two decoupled channels:
  1. *Internal In-App Notifications*: Stored in SQLite `notifications` for UI inbox display.
  2. *External Vendor Webhooks*: Dispatched over HTTP/HTTPS to tenant-registered webhook endpoints (`vendor_webhooks`).
* **Cryptographic HMAC-SHA256 Signing**: Every outbound webhook delivery payload is cryptographically signed using the vendor's private secret key:
  - `X-ULPF-Signature-256`: Hex-encoded HMAC-SHA256 signature of `${timestamp}.${rawJsonBody}`.
  - `X-ULPF-Timestamp`: Unix epoch millisecond timestamp preventing replay attacks.
  - `X-ULPF-Event`: Event category identifier (`SCHEMA_DRIFT`, `INGESTION_FAILURE`, `TEST_PING`).
* **Circuit-Breaker & Flapping Mitigation**: Tracks consecutive delivery errors per endpoint. A single transient blip remains non-fatal; after 3 consecutive failures, the endpoint is transitioned to `FAILING` and an inbox notification is dispatched to notify the vendor of webhook delivery failure.
* **SSRF Hardening**: Rejects webhook registration attempts targeting loopback addresses (`127.0.0.1`, `::1`, `localhost`), link-local/cloud metadata services (`169.254.169.254`), or malformed URI protocols.

---

### 3.9 Admin Accounts: Design Decisions and Why

What the feature does is summarised under 3.1. This section records **why** it is built this way, so that later changes do not undo a decision without knowing what it protects. History and test notes: `docs/plans/2026-10-31-admin-accounts-setup-and-invites.md`.

| Decision | Why | Rejected alternative (and why) |
| :--- | :--- | :--- |
| Admins live in SQLite; `.env` only holds one-time setup credentials | A config file is not an account store. It was re-applied on every restart, a rename left the old admin behind with its old password, and it allowed only one admin | Keep the `.env` admin: those three problems stay |
| Setup asks for the `.env` setup credentials before creating the super-admin | Between "the stack started" and "the operator finished setup", anyone who reaches the UI could otherwise claim the instance. Only the person with the server's `.env` has those credentials | "First visitor wins" setup: open to anyone on the network |
| Blank setup credentials disable setup | Fail closed: a misconfigured server cannot be claimed by anyone | Defaults such as `Admin@12345`: public, so not a secret |
| Super-admin = an `ADMIN` with a flag, plus a partial unique index | Every existing `ADMIN` check (backend rules, alerts, frontend, Grafana) keeps working. The index makes "one super-admin" a database guarantee, which also holds when two setups race | A new `SUPER_ADMIN` role: would change every role check and add a fourth login option |
| Role and super-admin status are read from the database on every request (`AccountLookup`, 60 s cache) | A token is only proof of who you were at sign-in. Without the lookup, a revoked admin kept admin rights for up to 24 h, and a forged or stale claim would be believed. The cache keeps it to at most one database read per user per minute | Trust token claims: fast, but revoke would not work |
| In-house `ConcurrentHashMap` cache, not Caffeine | Its size is bounded by the number of accounts (only validly signed tokens reach it), which is small on a control plane. No new dependency to download, update or audit | Caffeine: built for millions of entries and eviction policies we do not need |
| Tokens carry `pwv`, a 16-character fingerprint of the password hash | A password reset must end old sessions. Comparing the fingerprint with the live account does that without storing a session list. Tokens issued before this change have no `pwv` and are rejected, which forces one re-login | Token deny-list: more state to store and clean up |
| Link tokens: 32 random bytes; store only the SHA-256 hash | 256 random bits cannot be guessed, so a fast hash is enough. BCrypt's slowness (~50 ms) protects passwords people choose, not random tokens. Storing only the hash means a database leak gives no usable links (same idea as vendor API keys) | BCrypt for tokens: slow for no gain. Storing raw tokens: a leaked database leaks working links |
| Links are single use, 12 h, claimed with one conditional `UPDATE` | A shared link can be forwarded or leaked, so its life is short. One conditional statement means two simultaneous uses cannot both win, with no lock code | Check-then-update in code: two requests can both pass the check |
| The form is checked before the link is claimed; claim and account write run in one transaction | A typo (taken username, short password) must not burn the link. If the account write fails, the claim rolls back. The BCrypt hash is computed before the first write, so SQLite's write lock is held only for the two writes | Claim first: one typo makes the super-admin send a new link |
| Every unusable link answers `410` with the same message | The caller learns nothing about which links exist, were used, or were cancelled | Distinct errors: leak information to whoever holds a guessed token |
| Invite links shared by hand; no email | Air-gapped deployments have no mail server | Email invites |
| Revoke deletes the account (with its notifications and links) | A removed admin should leave nothing behind that could be re-enabled or confused with a live account (team lead's decision, 2026-10-31). Refused if vendor data references the account, so vendor data is never destroyed | Disable instead of delete: keeps rows nobody owns |
| Lost super-admin password: server command only (`reset-super-admin.sh`), no button or endpoint | Nobody signed in can vouch for the super-admin. Whoever can run container commands can already read the database and `.env`, so the command adds no new way in. It opens only SQLite, not the whole Spring app (no ClickHouse, no ONNX model), so it is fast and safe while the server runs | A "forgot password" endpoint: a network path to the most powerful account |
| Admin passwords 12-100 characters (USER/VENDOR keep 6-100) | Admin accounts can approve vendors and change mappings, so they get a stronger minimum (team lead's decision) | One rule for everyone: weak for admins, or a breaking change for existing users |
| Generated setup password: 10 characters, no look-alikes (0/O, 1/l/I) | It is typed by hand once and then never works again. About 58 bits is far beyond online guessing for a one-time value | A long random string: hard to type for no real gain |
| Onboarding endpoints closed to admins; approval promotes only a `USER` | An admin who submitted an onboarding request was turned into a `VENDOR` on approval (backlog item 11, Problem B). Two independent guards: the security rule, and the role check in `OnboardingService` | UI-only hiding: the API stayed open |
| One-time cleanup of legacy admins, keyed on the missing `is_super_admin` column | The column is created in the same transaction, so the cleanup runs exactly once per database, and admins created later survive every restart. A legacy admin that vendor data references is locked, not deleted, so startup never fails and no vendor data is lost | Clean up on every start: would delete invited admins |

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
