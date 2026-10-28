# ULPF Engineering Backlog & To-Do List

## High Priority: Data Plane & Replay Pipeline

### 1. Raw-to-Dynamic Backfill / Replay Worker [COMPLETED]
- **Problem**: When a new source is onboarded or its mapping is approved, historical events that were ingested prior to approval/provisioning reside only in `ulpf_raw.raw_events`.
- **Requirement**: Build a batch backfill / replay service (or worker) that reads unmapped records from `ulpf_raw.raw_events` for a given `source_id` and time range, applies the active mapping schema, transforms them into canonical event structures, and inserts them into the target dynamic table `ulpf_events.events_{source}`.
- **Design Considerations**:
  - Idempotency using `event_id` to prevent duplicate entries in ClickHouse.
  - Rate-limited chunking / batching to avoid ClickHouse memory spikes or lock contention.
  - Status tracking (e.g. CLI command or admin API `POST /api/v1/sources/{id}/replay` with job progress reporting).

### 2. Table Ingestion Failure & Drift Notification System [COMPLETED]
- **Problem**: When a log source emits events but the dynamic table has not been provisioned or is missing required columns, logs are safely stored in `ulpf_raw.raw_events` but fail to insert into `ulpf_events.events_{source}`.
- **Requirement**: Notify system admins and the respective vendor via `NotificationService` / webhook alerts when:
  - Events cannot be loaded into the dynamic table (e.g. table does not exist or schema mismatch).
  - Unmapped schema drift rate exceeds acceptable thresholds.
- **Implementation Summary**:
  - Built `TableIngestionFailureNotificationService` with sliding window throttling (default: 30-minute quiet window), atomic event drop accumulators, and digest dispatch.
  - Hooked failure detection directly into `ClickHouseIngestionRepository.flushCanonical()` for missing target table metadata, missing/unprovisioned tables, and batch insert exceptions.
  - Upgraded `SchemaDriftNotificationService` and `EventIngestionService` with high drift ratio (>30%) and total drift (100% unmapped fields) detection with actionable replay recovery instructions (`POST /v1/admin/sources/{sourceId}/replay`).
  - Added `DynamicSchemaProvisioningService.addColumnIfNotExists()` for schema column reconciliation.
  - Validated with unit tests in `TableIngestionFailureNotificationServiceTest`, `SchemaDriftNotificationServiceTest`, and `ClickHouseIngestionRepositoryTest` (243/243 test pass rate).
- **Design Considerations**:
  - Deduplicate and throttle notifications to prevent alerting spam.
  - Provide actionable metadata (vendor ID, source ID, affected event counts, missing table name).

### ~~3. Vendor-Scoped Self-Service Data Portal (Tenant Isolation & RBAC)~~ [FALSE POSITIVE]
- **Problem**: Currently, any authenticated client or admin can request exports or analytics across all sources and vendors unless filtered in the request. Vendors need secure, tenant-isolated access to upload logs and download exported data (e.g. Parquet / JSON) strictly restricted to their own `vendor_id` and assigned `source_id`s.
- **Requirement**:
  - Implement vendor authorization filters checking user session / API credential vendor identity.
  - Export endpoints (`GET /analytics/export/parquet`, etc.) must enforce `vendor_id == currentUser.vendorId` and verify `source_id` belongs to that vendor.
  - Provide a vendor upload endpoint (e.g., `POST /api/v1/vendor/sources/{sourceId}/upload`) allowing batch log ingestion (JSON/CSV/GZ) for their own log sources.
  - Audit logging for all vendor data download and upload operations.
- **Design Considerations**:
  - Strict multi-tenant row-level access control (prevent Insecure Direct Object Reference / IDOR vulnerabilities).
  - Rate limiting and payload size limits on bulk uploads.
  - Signed temporary download URLs or streaming responses for large Parquet export files.
- **Resolution (false positive)**: The stated problem does not exist in the code. `/v1/analytics/**` and `/v1/admin/**` are restricted to `ADMIN` in `SpringSecurity.java`, and `AnalyticsController` carries a class-level `@PreAuthorize("hasRole('ADMIN')")`, covering `/analytics/export/parquet` and `/analytics/import/file`. Vendors and regular users cannot request exports or analytics, so there is no cross-tenant leak today, and the frontend has no export feature. The only `VENDOR`-accessible controller is `VendorWebhookController`. A vendor-facing portal would be a new feature, not a fix; if it is ever wanted, re-file it as such and start with a plan (verify whether the JWT/user model carries a `vendorId`).

### 4. Non-Compliant Trace ID Vendor Notification & Source-Scoped Tracing [ON HOLD]
- **Problem**: When vendors emit log events with invalid or non-W3C compliant trace identifiers (e.g., non-hex characters, invalid lengths, or truncated strings like `"abc"`), the system sets `trace_id` to `null` to avoid corrupting distributed traces. However, vendors intending to correlate events receive no feedback that their IDs were dropped. Furthermore, in large enterprise deployments with hundreds of sources, independent sources may inadvertently generate colliding trace IDs.
- **Requirement**:
  - Build an asynchronous alert / notification job via `NotificationService` that informs vendors when their emitted trace IDs fail W3C validation (`[0-9a-f]{32}` or standard UUID), providing sample rejected values and standard compliance guidelines.
  - Throttle / rate-limit notification generation per `(vendor_id, source_id)` to avoid alert fatigue or database flooding on high-throughput log streams.
  - Enforce composite trace isolation using `(source_id, trace_id)` as the unique trace lookup key in queries, analytics, and lineage tracking, preventing cross-source trace collisions.
- **Design Considerations**:
  - Maintain an in-memory or sliding window counter to deduplicate warnings per source per hour.
  - Ensure zero performance degradation on the high-throughput ingestion critical path.
- **Status (on hold)**: Parked until the infrastructure can support it. Trace IDs are not persisted or queryable today (no `trace_id` in the ClickHouse schema or analytics queries; they only live in the request context, response header and logging), so source-scoped tracing has nothing to scope yet. Also, most vendors will not send a trace ID at all, so the notification behaviour needs a configurable design (per-vendor/source opt-in, what counts as "expected" vs. "non-compliant") and likely some redesign outside this item. Revisit once trace storage and per-source configuration exist, and start with a plan.

### 5. Decouple Dynamic Table Provisioning from Source Names (Domain-Level Canonical Tables)
- **Problem**: Dynamic schema provisioning currently defaults table names to `ulpf_events.events_{sourceName}` (e.g. `events_firewall_alpha`). This creates a table-per-source anti-pattern, violates the canonical domain model outlined in `DATABASE_SCHEMA.md` Section 4 (`network_events`, `web_events`, `sensor_events`, `database_events`), and leads to ClickHouse table sprawl. Even with the 60% column similarity re-use heuristic, subsequent sources end up writing to a table named after whichever source was approved first (e.g., a "Firewall Beta" stream writing into `events_firewall_alpha`).
- **Requirement**:
  - Decouple table naming from source names and map directly to canonical domain/category tables based on `source_type` or `log_type` (e.g., `FIREWALL` $\rightarrow$ `ulpf_events.network_events`, `WEB_APP` $\rightarrow$ `ulpf_events.web_events`, `SENSOR` $\rightarrow$ `ulpf_events.sensor_events`, `DATABASE` $\rightarrow$ `ulpf_events.database_events`).
  - Tables must remain partitioned by `(vendor_id, source_id, toYYYYMM(timestamp))` to guarantee strict multi-tenant source isolation while allowing thousands of sources to share a single consolidated MergeTree table.
  - Support dynamic schema evolution on the domain table (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`) when new sources introduce new canonical fields.
  - Implement a migration or alias redirection for legacy `events_{source}` tables to ensure backward compatibility with existing ClickHouse data.
- **Design Considerations**:
  - Avoid ClickHouse part/metadata explosion by maintaining a small, bounded set of domain tables.
  - Update analytics queries and export endpoints to query domain tables filtered by `source_id` rather than dynamic source-slug table names.
  - Centralize domain table mapping configuration in SQLite/code.

### 6. Replay Worker Optimizations & Persistent Job Tracking
- **Problem**: While `feature/replay-backfill` successfully implements the core asynchronous replay pipeline, several performance and resilience optimizations were identified during review:
  1. *Redundant Dedup Queries on Clean Sources*: For newly approved sources where the dynamic table is empty, the worker executes `WHERE event_id IN (...)` on every single chunk, generating hundreds of unnecessary ClickHouse round-trips that always return zero duplicates.
  2. *Chunk Sizing for Bulk Operations*: The default chunk size (1,000) creates thousands of small ClickHouse batch inserts for sources with millions of events, risking ClickHouse "too many parts" errors under high backfill volumes.
  3. *Arbitrary 30-Day Auto-Trigger Window*: `ReplayBackfillService.triggerReplayAsync()` currently defaults `fromTime` to `now - 30 days`, scanning 30 days of data even when a schema drifted just hours earlier.
  4. *In-Memory Job State Volatility*: `activeJobs` resides in a `ConcurrentHashMap`; a server restart clears replay status history and prevents resuming interrupted jobs from their exact cursor position.
- **Requirement**:
  - *Empty Table Fast-Path*: Check `count() == 0` on target table at start of replay; if zero, bypass `findExistingEventIds` pre-checks entirely and stream-insert at maximum network throughput.
  - *Bulk Chunk Scaling*: Increase default chunk size to 10,000–25,000 records for bulk backfill, or dynamically scale chunk size based on estimated total events.
  - *Drift-Aware Lookback*: Default `fromTime` to the corresponding `onboarding_requests.created_at` timestamp rather than hardcoded 30 days.
  - *Persistent Job Ledger in SQLite*: Persist `replay_jobs` (`job_id`, `source_id`, `target_table`, `status`, `events_processed`, `cursor_timestamp`, `cursor_event_id`) to SQLite so jobs survive application restarts and can resume from their exact checkpoint.
- **Design Considerations**:
  - Ensure cursor persistence updates periodically (e.g., every 5,000 events) rather than per-event to minimize SQLite write contention.
  - Keep ClickHouse memory pressure throttling active regardless of chunk size.

### 7. Production Observability: Strip Legacy UI Grafana & Integrate Real Prometheus + Grafana Stack [PARTIALLY COMPLETED]
- **Problem**: The frontend currently contains a custom, ad-hoc "Grafana" view that is brittle, unstable, and lacks real observability capabilities. Admins cannot reliably monitor pipeline throughput, log volume spikes, ClickHouse insert latency, buffer backpressure, or error rates across the ingestion cluster.
- **Requirement**:
  - ~~Completely strip out the legacy pseudo-Grafana frontend component and its associated mock routes and assets from the frontend codebase.~~ **[DONE]**
  - Integrate an industry-standard open-source observability stack using **Prometheus** and **Grafana** into the platform deployment configuration (e.g. `docker-compose.yml`).
  - Configure **Micrometer & Spring Boot Actuator** (`/actuator/prometheus`) in `core-engine` to export production metrics:
    - *Ingestion Throughput*: Events ingested per second segmented by `source_id`, `vendor_id`, and `source_type`.
    - *Queue & Backpressure*: In-memory buffer depth, worker thread pool utilization, and event drop counters.
    - *ClickHouse Analytical Pipeline*: Batch flush frequency, batch size distribution, flush duration/latency, and insert error counts.
    - *Alerting & Webhook Metrics*: Schema drift rates, incident counts, and webhook delivery latency/success ratios.
  - Provide pre-configured Grafana dashboards (provisioned via code/JSON) with datasources automatically wired to Prometheus and ClickHouse (via ClickHouse Grafana plugin), giving administrators immediate visibility into log spikes, system health, and pipeline errors.
  - Recreate the removed full-text search and log-volume/error-spike histogram as Grafana panels over `ulpf_raw.raw_events` (e.g. `positionCaseInsensitive` / `match()` filters and `toStartOfInterval` bucketing), so no feature is lost with the removal of `/v1/analytics/search` and `/v1/analytics/timeseries`.
  - Restrict Grafana's ClickHouse access with a dedicated **read-only** ClickHouse user (limited to `ulpf_raw` / `ulpf_events`, with `max_execution_time`, `max_memory_usage`, and `max_result_rows` profile limits).
  - Tighten `/actuator/**` security: it is currently `permitAll()` in `SpringSecurity.java` and `JwtFilter.java`; only `/actuator/health` may stay open, and `/actuator/prometheus` must be restricted.
- **Design Considerations**:
  - Keep `/actuator/prometheus` endpoints protected or restricted to internal Docker networks / admin authentication.
  - Ensure metric recording uses lightweight Micrometer counters/timers with sub-microsecond overhead off the ingestion hot path.
  - Provide a clean admin portal navigation action / link to launch the dedicated Grafana dashboard.
  - **RBAC gap**: Grafana connects to ClickHouse directly and bypasses the application's `@PreAuthorize("hasRole('ADMIN')")` checks (which previously guarded the removed search/timeseries endpoints). Access control must be re-established at the Grafana layer: Grafana login limited to admins (SSO/OIDC against ULPF auth, or a reverse-proxy auth check), anonymous access disabled, and no per-vendor data exposed to non-admin viewers. Revisit this together with item 3 (vendor-scoped data portal) if vendors are ever given dashboards.
  - Do not expose Grafana, Prometheus, or ClickHouse ports publicly in `compose.yaml`; keep them on the internal `ulpf-net` network behind the authenticated proxy.
- **Implementation Summary (completed part: legacy UI removal)**:
  - Removed the "Grafana Search & Graphs" mode from `AnalyticsPage.jsx`: tab button, search form, results view (histogram + log viewer), state, `handleGrafanaSearch()` (including its hardcoded fake-data fallback), `highlightQuery()`, and every `mode === "grafana"` branch. Builder, Direct SQL, and Lineage modes are unchanged.
  - Removed `GET /v1/analytics/search` and `GET /v1/analytics/timeseries` from `AnalyticsController`, and `searchLogs()`, `getTimeSeries()`, `resolveIntervalSeconds()`, `generateMockTimeSeries()`, `LogSearchResult`, and `TimeSeriesBucket` from `AnalyticsService`. No other code, tool, or script used them.
  - Removed the 9 tests that covered only the deleted code from `AnalyticsControllerTest` and `AnalyticsServiceTest` (full `core-engine` suite: 320/320 passing; frontend production build verified).
  - Updated `README.md`, `docs/ARCH/API_SPECIFICATION.md`, `docs/ARCH/SCALABILITY_AND_CLUSTER_GUIDE.md`, and `docs/frontend/frontend.md` to drop the removed endpoints and mode.
  - Kept the `tokenbf_v1` Bloom filter index on `raw_events.raw_payload` for use by Grafana's ClickHouse queries.
- **Remaining**: Prometheus + Grafana in `compose.yaml`, Micrometer/Actuator metrics, provisioned dashboards/datasources, and the RBAC and hardening items above.

### 8. Modular Architecture Refactoring: Decoupled Mapping & Transformation Pipeline
- **Problem**: The current mapping engine logic is tightly coupled across monolithic classes (`MappingProposalService`, etc.). Header detection, raw payload parsing, type casting, schema version resolution, proposal generation, and candidate promotion are intertwined in dense blocks. This violates the Single Responsibility Principle, creates merge conflicts, and prevents multiple developers from working on mapping features in parallel.
- **Requirement**:
  - Refactor the mapping engine into a decoupled, layer-by-layer modular pipeline:
    1. *Payload Parsing & Normalization Layer*: Pluggable extractors for JSON, CSV, Syslog, Key-Value, and Delimited formats (`PayloadExtractor` SPI).
    2. *Field Extraction & Auto-Detection Layer*: Isolated strategy classes for header inspection, sample profiling, and column candidate deduction (`FormatDetector`, `FieldDetector`).
    3. *Transformation & Mapping Pipeline*: Dedicated transformers for type coercion (string to int/datetime/uuid), expression evaluation, and canonical field alignment (`ValueTransformer`, `MappingRule`).
    4. *Schema Versioning & Promotion Lifecycle*: Pure state machine handling candidate proposals, version increments (`v1`, `v2`, ...), fallback resolution, and approval promotion.
  - Define clear Java interfaces (`MappingExtractor`, `FieldTransformer`, `MappingValidator`, `ProposalStrategy`) so new formats and transformations can be implemented independently without touching core engine orchestration.
  - Enable concurrent developer contributions by isolating domain models, unit tests, and strategy implementations into dedicated packages under `com.ulpf.core.mapping.*`.
- **Design Considerations**:
  - Preserve 100% backward compatibility with existing stored mapping JSON schemas and SQLite tables.
  - Performance: Avoid unnecessary intermediate object allocation on the live ingestion hot path.
  - Comprehensive unit test isolation for each individual strategy and transformation step.
- **Note**: The proposal-generation part of this item (`ProposalStrategy`, field candidate deduction) is superseded by item 10 (LLM mapping agent). Payload parsing and value transformation remain in scope here.

### 9. Modular Frontend Refactoring: Split `AnalyticsPage.jsx` into Per-Mode Components
- **Problem**: `frontend/src/pages/AnalyticsPage.jsx` is still ~1,700 lines even after removing the legacy Grafana mode. Builder, Direct SQL, and Lineage modes, the Parquet export, and the bulk-import modal all share one component with a large amount of interleaved state and `mode === ...` conditionals, making it hard to read, test, or change in parallel.
- **Requirement**:
  - Split the page into focused components under `frontend/src/pages/analytics/` (or similar): one per mode (`BuilderMode`, `SqlMode`, `LineageMode`), plus `BulkImportModal`, a shared results/metrics panel, and a slim `AnalyticsPage` that owns only mode switching and shared state (`viewState`, `loading`, `errorMessage`).
  - Extract the dynamic table/column loading logic into a custom hook (e.g. `useClickHouseSchema`) and API calls into `api/analytics.js` instead of inline `client.get/post` calls.
  - Remove the repeated `mode === "lineage" ? ... : ...` ternaries in favor of per-mode components that render their own labels and results.
- **Design Considerations**:
  - Pure refactor: no behavior, endpoint, or styling changes; verify each mode manually (or with tests) before and after.
  - The frontend currently has no test runner; add Vitest + React Testing Library alongside this work so the extracted components are covered.

### 10. Replace the 4-Layer Mapping Engine with a Single LLM Mapping Agent [Adarsh has assumed the ownership]
- **Plan**: [`docs/plans/2026-10-28-llm-mapping-agent.md`](docs/plans/2026-10-28-llm-mapping-agent.md) (Status: Draft, awaiting approval).
- **Problem**: The alias → TF-IDF → Levenshtein → ONNX cascade decides each field from its name alone, with no sample values or context and no explanation. In practice it is weaker than it looks:
  - Layer 4 uses a placeholder hash tokenizer instead of MiniLM's real one, and `mapping_embeddings` is never populated, so it cannot produce meaningful matches.
  - TF-IDF is trained on 10 examples.
  - The alias seeds (`source.ip`) and the TF-IDF and description data (`src_ip`) use different canonical vocabularies.
  - The admin review UI fabricates layer names and confidence scores.
- **Requirement**:
  - One decision layer: a locally hosted, instruction-tuned Qwen or Gemma model (chosen by measurement on a labelled eval set), running as a sidecar behind an OpenAI-compatible API.
  - Simple in-house vector store (SQLite plus in-memory cosine) holding embeddings of canonical fields, existing tables and their schemas, mapping rules, and human-approved mappings. The agent gets a read-only search handle only.
  - RAG over versioned rule files, plus a versioned system prompt.
  - The agent has no tools and no DB or network access. Context is assembled in Java; output is strict JSON checked by a deterministic validator (closed vocabulary, reserved columns, DDL-safe names); a human still approves.
  - Per-field decision (`MAP` / `NEW_FIELD` / `UNMAPPED`), model-stated confidence, reasoning and evidence, plus a target-table recommendation, shown in the admin UI.
  - Async onboarding (`AI_ANALYSIS` → `HUMAN_REVIEW`) so inference never runs inside the submit request.
  - Full audit record per run (model digest, prompt version, rules hash, retrieved context, raw and validated output).
  - Ingestion hot path (`MappingTransformer`, ingestion, replay) unchanged; `mapping_json` changes are additive only.
- **Phases**:
  - 0: spike, gold eval set and legacy baseline, model choice.
  - 1: `MappingProposer` port, canonical field registry, sample profiler, fix metadata loss on admin edit.
  - 2: vector store and indexer, model sidecar in compose.
  - 3: agent core, validator and audit, behind a flag.
  - 4: async onboarding and target-table recommendation.
  - 5: frontend, with Vitest added first.
  - 6: eval gate, then delete the legacy layers, the ONNX model and their tests.
- **Design Considerations**:
  - CPU-only inference latency and RAM on the current host (16 cores, 15 GB, no GPU); measure before choosing the model size.
  - Prompt injection via vendor sample logs; LLM confidence is not calibrated, so nothing is auto-approved.
  - Interacts with item 5 (domain-level tables) through the target-table recommendation, and supersedes part of item 8.
  - Open questions (naming convention, runtime, hardware, fallback policy, PII in prompts, `NEW_FIELD`, eval thresholds) are listed in the plan, §7.
