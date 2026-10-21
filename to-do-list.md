# ULPF Engineering Backlog & To-Do List

## High Priority: Data Plane & Replay Pipeline

### 1. Raw-to-Dynamic Backfill / Replay Worker [COMPLETED]
- **Problem**: When a new source is onboarded or its mapping is approved, historical events that were ingested prior to approval/provisioning reside only in `ulpf_raw.raw_events`.
- **Requirement**: Build a batch backfill / replay service (or worker) that reads unmapped records from `ulpf_raw.raw_events` for a given `source_id` and time range, applies the active mapping schema, transforms them into canonical event structures, and inserts them into the target dynamic table `ulpf_events.events_{source}`.
- **Design Considerations**:
  - Idempotency using `event_id` to prevent duplicate entries in ClickHouse.
  - Rate-limited chunking / batching to avoid ClickHouse memory spikes or lock contention.
  - Status tracking (e.g. CLI command or admin API `POST /api/v1/sources/{id}/replay` with job progress reporting).

### 2. Table Ingestion Failure & Drift Notification System
- **Problem**: When a log source emits events but the dynamic table has not been provisioned or is missing required columns, logs are safely stored in `ulpf_raw.raw_events` but fail to insert into `ulpf_events.events_{source}`.
- **Requirement**: Notify system admins and the respective vendor via `NotificationService` / webhook alerts when:
  - Events cannot be loaded into the dynamic table (e.g. table does not exist or schema mismatch).
  - Unmapped schema drift rate exceeds acceptable thresholds.
- **Design Considerations**:
  - Deduplicate and throttle notifications to prevent alerting spam.
  - Provide actionable metadata (vendor ID, source ID, affected event counts, missing table name).

### 3. Vendor-Scoped Self-Service Data Portal (Tenant Isolation & RBAC)
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

### 4. Non-Compliant Trace ID Vendor Notification & Source-Scoped Tracing
- **Problem**: When vendors emit log events with invalid or non-W3C compliant trace identifiers (e.g., non-hex characters, invalid lengths, or truncated strings like `"abc"`), the system sets `trace_id` to `null` to avoid corrupting distributed traces. However, vendors intending to correlate events receive no feedback that their IDs were dropped. Furthermore, in large enterprise deployments with hundreds of sources, independent sources may inadvertently generate colliding trace IDs.
- **Requirement**:
  - Build an asynchronous alert / notification job via `NotificationService` that informs vendors when their emitted trace IDs fail W3C validation (`[0-9a-f]{32}` or standard UUID), providing sample rejected values and standard compliance guidelines.
  - Throttle / rate-limit notification generation per `(vendor_id, source_id)` to avoid alert fatigue or database flooding on high-throughput log streams.
  - Enforce composite trace isolation using `(source_id, trace_id)` as the unique trace lookup key in queries, analytics, and lineage tracking, preventing cross-source trace collisions.
- **Design Considerations**:
  - Maintain an in-memory or sliding window counter to deduplicate warnings per source per hour.
  - Ensure zero performance degradation on the high-throughput ingestion critical path.

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




