# Universal Log Processing Framework (ULPF)
## High-Level System Architecture — Production Reference

---

## 1. Core Objective

ULPF (Universal Log Processing Framework) is an enterprise-grade log ingestion and AI-assisted field mapping platform. It ingests heterogeneous multi-vendor log streams at high throughput (10,000+ EPS), preserves raw payload lineage, and maps raw keys to canonical schemas.

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
│  │ CONTROL PLANE                │        │ DATA PLANE (Ingestion)                  │  │
│  │ • Onboarding Workflow        │        │ • Header-Based Protocol Autodetector    │  │
│  │ • AI Mapping Engine          │        │ • In-Memory Queue Buffer                │  │
│  │ • Replay & Backfill Worker   │        │ • ClickHouse Async Micro-Batching       │  │
│  │ • Decoupled AlertDispatcher  │        │ • Schema Drift & Failure Governance     │  │
│  │ • Vendor Webhook Governance  │        │                                         │  │
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
└────────────────────────────────────────┘    └─────────────────────────────────────────┘
```

---

## 3. Core Architectural Modules

### 3.1 Control Plane
* **User Authentication & Role Governance**: Enforces JWT Bearer token authentication supporting `ADMIN`, `VENDOR`, and `USER` roles. `JwtFilter` checks every token against the live account (cached 60 s, `AccountLookup`): role and super-admin status come from SQLite, and a password change (`pwv` fingerprint claim) or revoke ends sessions on the next request.
* **Admin Accounts**: No account is created from `.env`. The first visit to `/setup` asks for the one-time setup credentials (`ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD`) and creates the single **super-admin** (`users.is_super_admin`, unique index). The super-admin invites regular admins and resets their passwords with single-use 12 h links (`admin_links`, SHA-256 hashed), and revokes them (account deleted). A lost super-admin password is reset only on the server (`startup/reset-super-admin.sh`, `ResetSuperAdminCommand`). Admin passwords are 12-100 characters. Onboarding is closed to admins, so an admin can never become a vendor. Why each choice was made: §3.9.
* **Vendor & Log Source Management**: Manages vendor identities, log sources, and active API key credentials with microsecond RAM caching and 5-minute idle eviction. A source has at most one active key. The vendor creates it after approval and can regenerate it (`ApiKeyService`, see §3.10).
* **Onboarding Workflow**: Accepts sample log payloads and schema files, runs AI field discovery, and queues candidate mappings for admin review. Submit creates no API key; approval activates the source and tells the vendor to create one.

### 3.2 AI Mapping Engine (Layers 1 – 4)
* **Layer 1 (Exact Match)**: Instant SQLite dictionary lookup.
* **Layer 2 (TF-IDF Similarity)**: N-gram term frequency-inverse document frequency cosine matching.
* **Layer 3 (Typo Tolerance)**: Levenshtein edit distance matching.
* **Layer 4 (Vector Embeddings)**: Local ONNX `all-MiniLM-L6-v2` 384-dimensional vector cosine similarity.
* **Human Feedback Learning Loop**: Captures manual admin adjustments to continuously refine candidate confidence scores.

### 3.3 Data Plane (Ingestion)
* **Protocol Autodetector**: Automatically parses Syslog (RFC 3164/5424), CEF, LEEF, and JSON payloads.
* **Two body forms, one event each**: `application/json` (an object, or a JSON string holding one raw line), and any other type (or none) for one raw line. `EventController` reads a raw line from the request stream and decodes it with the declared charset, or UTF-8. The content type decides only how the body is read; the autodetector decides the format.
  * *Why the raw stream, not `@RequestBody`:* for a form type (the `curl -d` default), Spring rebuilds the body from parsed form fields and re-encodes it, which changes the line; and Spring's String converter defaults to ISO-8859-1, which garbles UTF-8. Before 2026-11-15 the endpoint bound every body to `Object`, so every plain-text line got `415` (plan `docs/plans/2026-11-15-plain-text-ingest.md`).
  * *Why `consumes = "!application/json"` on the text handler and a required JSON body:* the two mappings must not overlap. With an optional JSON body, both matched an empty request, and Spring answered `409` (ambiguous handler).
  * *Rejected:* a custom message converter from text to `Object`. It would change how every `Object` body in the application is read, to fix one endpoint.
* **Micro-Batching**: Enqueues events into unbounded `ConcurrentLinkedQueue` buffers (raw and canonical). A flush sends a batch to ClickHouse as an async insert (`async_insert=1`, `wait_for_async_insert=1`) when a queue reaches 500 events, or every 1,000 ms from the scheduler.
* **Where the cost lands today**: the request thread that fills a queue to 500 runs the flush itself. `flush()` and `flushCanonical()` are `synchronized` on the same object (`ClickHouseIngestionRepository`). So other requests can wait for a flush. The AI mapping runs at onboarding, not at ingest. Backlog item 13 measures this path and changes it.

### 3.4 Integrity Ledger: removed on 2026-11-19
Until 2026-11-19, each raw flush wrote a SHA-256 Merkle block per source into SQLite (`batch_integrity_blocks`), and `/v1/integrity/*` verified the blocks against ClickHouse. It was removed: it did not detect edits to SQLite or deleted blocks, it ran inside the raw flush lock and slowed ingestion as it grew, and no requirement asked for it. The reasons, the rejected alternatives and the old design are in [`INTEGRITY_LEDGER.md`](INTEGRITY_LEDGER.md) (status: Removed). Raw data preservation (SIH a) and traceability (SIH d) come from `ulpf_raw.raw_events` and `lineage_id`, not from the ledger.

### 3.5 Dynamic ClickHouse Schema Provisioning
* **Dynamic Table Engine**: Automatically provisions isolated ClickHouse tables (`events_{vendor}_{source}`) upon onboarding approval.
* **Lossless Schema Overflow**: Unmapped raw vendor payload fields route into a `raw_unmapped` column, eliminating runtime `ALTER TABLE` locks while guaranteeing 100% zero data loss.

### 3.6 Asynchronous Historical Replay & Backfill Engine

![Replay Backfill Pipeline Architecture](../../Architecture_Diagram/Replay%20Backfill%20Pipeline%20Architecture.png)

* **On-Demand Execution**: Runs on demand via the Admin REST API (`POST /v1/admin/sources/{sourceId}/replay`). Approval no longer starts a replay (removed on 2026-11-12): before approval no API key exists, so the source has no raw events to replay.
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

### 3.10 API Keys: Design Decisions and Why

| Decision | Why | Rejected alternative (and why) |
| :--- | :--- | :--- |
| No key exists before approval. After approval, the vendor clicks **Create API key** and sees the key once | A key handed out at submit could not be used: ingest accepts only `ACTIVE` keys, so the vendor held a key that was rejected until an admin acted. Nobody should hold a credential before a human has reviewed them | A key at submit (the old flow): a key that does not work, and the replay at approval always found 0 events. Accept raw events while pending: anyone who signs up could write to ClickHouse before review, which needs quotas and cleanup on reject |
| The key is generated when the vendor clicks, not when the admin approves | The raw key then exists only in that one HTTP response (`Cache-Control: no-store`). Only the SHA-256 hash is stored, as before | Create it at approval and keep it until the vendor fetches it: a raw key stored at rest |
| Delivery in the vendor's own session, no other channel | The stack runs air-gapped and has no mail server. Only the vendor sees the key | Email: not available. Vendor webhook: sends a secret to an outside URL. An admin hands it over: the admin would know the vendor's key |
| Regenerate is the same action, with `replaceExisting: true` and a confirmation; the old key is revoked in the same transaction | One code path for the first key and every replacement. The two reasons to regenerate are a lost key (nobody uses the old one) and a leaked key (it must stop at once) | A grace period with two valid keys: keeps a leaked key alive. A separate rotate endpoint: duplicate logic |
| A partial unique index allows one `ACTIVE` key per source | Two requests that arrive together cannot both leave a working key | A check in code only: two transactions can both pass it |
| The revoke evicts the cache entry before the update and again after the commit | Until the commit, an ingest request on another connection still reads the old key as `ACTIVE` and can put it back in the cache. Cache hits refresh the idle timer, so a busy client could keep a revoked key alive | Evict only before the update: leaves that window open |
| Only the source owner can create or regenerate a key; admins cannot | A key that an admin has seen is a key two people know. `/v1/onboard/**` admits `USER` and `VENDOR` only, and the service checks ownership | Admin-created keys: see the previous row |
| Every key creation notifies the vendor, without the key | If someone else used the vendor's account, the vendor sees it | No notification: a stolen session could replace the key silently |
| `PENDING_APPROVAL` removed from `credentials.status`, with no migration | No code writes it any more. There were no users, so existing dev databases were recreated instead | A table-rebuild migration in `SqliteSchemaMigrator`: code kept forever for nobody |

### 3.11 Metrics and Performance Measurement

**Metrics (`/actuator/metrics`).** Spring Boot Actuator and Micrometer, with no Prometheus export yet (the Grafana plan adds it):
- **ULPF meters:**
  - `ulpf.queue.raw.size` and `ulpf.queue.canonical.size`: gauges, bound in `MetricsConfig`.
  - `ulpf.clickhouse.flush.duration` (timer) and `ulpf.clickhouse.batch.size` (summary), both tagged `stage=raw|canonical`, with explicit buckets.
  - The failure counters `ulpf.clickhouse.insert.errors`, `ulpf.records.degraded`, `ulpf.records.dropped` and `ulpf.records.reenqueued`, tagged `table`. They are created on the first failure.

  The names, tags and buckets follow the metric table of `docs/plans/2026-10-28-prometheus-grafana-observability.md`.
- **Built-in meters:** HTTP requests, the JVM, HikariCP, and the Tomcat threads (`server.tomcat.mbeanregistry.enabled`).
- **Security** (`ActuatorSecurity`, its own `@Order(1)` chain): `/actuator/health` is open. Every other actuator endpoint needs the monitor user over HTTP Basic: `ulpf.monitor.username`, and the password from `MONITOR_PASSWORD` or `/run/secrets/monitor_password`.

**Measured results** are in `docs/perf/RESULTS.md`, with the host, the method and the limits of every number. The research behind the method is in `docs/research/2026-11-13-profiling-ingestion.md`. The tooling (k6, async-profiler, run and report scripts) is a local kit on the team lead's machine (`perf/`, git-ignored).

| Decision | Why | Rejected alternative (and why) |
| :--- | :--- | :--- |
| The monitor user lives in the actuator chain's own `ProviderManager`, not in a global `UserDetailsService` bean | A global bean would join the application's authentication, so the monitor password could sign in to the app. A ULPF login token also never opens the actuator, because `JwtFilter` skips `/actuator/**` | One shared chain with an extra role: mixes metric scraping with user logins |
| Fail closed: with no password, every actuator endpoint except health is denied, and a `WARN` is logged | Metrics show internals (queue depth, table names in counter tags). Nothing must be exposed by a missing setting | A default password: it is public. Open metrics: `/actuator/**` was `permitAll` before 2026-11-16 |
| The pipeline meters are registered once and kept in fields; per-table tags are only on the failure counters | Nothing is looked up per event. Timers with per-table tags multiply into thousands of series (measured in the Grafana plan) | Tagging the flush timer by table: cardinality |
| `MeterRegistry` reaches `ClickHouseIngestionRepository` by an `@Autowired` setter, with a `SimpleMeterRegistry` default | The class already takes its optional parts by setters, so the constructor and its tests stay unchanged | A second constructor (the Grafana plan's first idea): a different style in the same class |
| The load test offers a fixed rate (k6 open model) and judges on server-side counts per second | A closed-model test slows down when the server stalls and hides the stall (coordinated omission). The old 995 events/s figure came from such a test | Counting on the client, or the old Python scripts |
| The measured cause is fixed before anything else is changed | Phase 0 measured a lock convoy on the synchronized flush and O(n) queue-size calls (`docs/perf/RESULTS.md`), not a guess | Tuning first (heap, threads, network): it moves numbers without removing the cause |
| Each comparison starts on fresh volumes | The integrity ledger slowed ingestion as it grew: the raw flush took 35 ms on fresh volumes and 143 ms with 102,369 blocks, and the knee fell from 400 to 300 (`docs/perf/RESULTS.md`, 2026-11-16). The ledger was removed on 2026-11-19 (§3.4), so this cause is gone. Fresh volumes still give each run the same starting state. Measure before you drop the rule | Reusing one stack for all runs: each run then runs on a larger ledger than the one before, and the order of the runs biases the result |
| The containers stay on rootless Podman | On the same starting state, Podman 3.4.4 matched rootless Docker within the noise band (measured 2026-11-16; measure again on Podman 4.9, which the Grafana plan requires). The team lead's rule was to switch only on a large Podman disadvantage | Switching to Docker: no measured gain, and Docker needs its own fixes here (the ClickHouse health check, item 19; no `host.containers.internal` in rootless mode) |

## 4. Technology Stack & Packaging

| Layer | Component | Technology |
| :--- | :--- | :--- |
| **Frontend** | UI SPA | React 18, Vite, Tailwind CSS, Warm Kinetic Design System |
| **Web Server / Proxy** | Edge Proxy | Nginx Alpine (HTML5 SPA routing + API reverse proxy) |
| **Backend Engine** | Core API & Logic | Java 21, Spring Boot 4.1, HikariCP, Spring Security, JWT |
| **Backend Runtime Image** | Container Base | `eclipse-temurin:21-jre` (Ubuntu, glibc), not Alpine: see the decision below |
| **Control Database** | Metadata Store | SQLite 3 (`data/control-plane.db`) |
| **Data Database** | Analytical Storage | ClickHouse 26.3 LTS (`ulpf_raw` & `ulpf_events`) |
| **AI Model Engine** | Vector Embeddings | ONNX Runtime Java, `all-MiniLM-L6-v2` (Local Zero-RAM Lifecycle) |
| **Orchestration** | Container Runtime | Podman / Docker Compose (`compose.yaml`, `start.sh`, `start.bat`) |

**Decision (2026-11-15): the core engine runs on the glibc JRE image, not Alpine.** The ONNX Runtime native library that mapping layer 4 uses is built for glibc and needs `libstdc++`. On `eclipse-temurin:21-jre-alpine` (musl) it failed to load with `UnsatisfiedLinkError`, and every onboarding request returned 500. The image is about 124 MB larger (472 MB instead of 348 MB). Rejected: adding `libstdc++` to Alpine, because the library would still need a glibc compatibility layer, which ONNX Runtime does not support. As a second guard, `ModelLifecycleManager` turns a `LinkageError` into an `IllegalStateException` and does not retry, so a platform without the native library loses only layer 4, not onboarding. Rejected: catching `Error` in the layer fallback, because it would also hide `OutOfMemoryError`. Plan: `docs/plans/2026-11-15-onnx-native-runtime.md`.
