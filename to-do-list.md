# ULPF Engineering Backlog & To-Do List

## High Priority: Data Plane & Replay Pipeline

### 1. Raw-to-Dynamic Backfill / Replay Worker
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



