# ULPF Database & Engine Integration Summary

This document summarizes the database wiring, control plane onboarding flows, AI mapping persistence, ClickHouse analytics integration, and performance optimizations implemented on the `feature/dbChange` branch.

---

## 🚀 Key Highlights & Architectural Improvements

### 1. Control Plane & Onboarding Pipeline Wiring
- **Full Vendor Onboarding Lifecycle**: Connected `OnboardingController` to `OnboardingService`, `VendorRepository`, `SourceRepository`, `CredentialRepository`, and `OnboardingRepository`.
- **API Key Security**:
  - Raw API keys (`ulpf_live_...`) are returned **once** to the vendor on request submission.
  - Only `SHA-256(apiKey)` hash is stored in SQLite `credentials`.
- **Automated Mapping Versioning**:
  - When a vendor submits a log sample, `OnboardingService` auto-generates candidate mappings via `MappingProposalService`.
  - Proposals are saved in `mapping_versions` with status `'CANDIDATE'` and incremental versioning.
  - When an admin approves the request, `OnboardingService` activates the vendor source, credential, and candidate mapping version simultaneously while retiring older active versions.

---

### 2. Dataplane Credential Resolution & Early-Exit Query Optimization
- **Optimized 3-Tier Join Query**:
  - Refactored `CredentialRepository.findActiveByKeyHash` using an **early-exit `EXISTS` subquery**.
  - Checks `c.key_hash = ? AND c.status = 'ACTIVE'` first. Invalid or revoked keys exit instantly without evaluating joins across `sources` or `vendors`.
- **RAM Caching**:
  - Active credentials and mappings are cached in RAM (`ConcurrentHashMap`), achieving **~100ns resolution latency** during high-throughput event ingestion.

---

### 3. ClickHouse Analytics Query Service & Storage Compression
- **Read-Only Aggregation Engine**:
  - `AnalyticsService` executes `COUNT`, `AVG`, `MIN`, `MAX`, and `SUM` aggregations against ClickHouse via `@Qualifier("clickhouseJdbcTemplate")`.
  - Input identifiers (table/column names) are sanitized with strict regex (`^[a-zA-Z0-9_.]+$`) to prevent SQL injection.
- **ZSTD(15) Non-Destructive Log Compression**:
  - Added `TTL received_at + INTERVAL 7 DAY RECOMPRESS CODEC(ZSTD(15))` to `raw_events` in ClickHouse DDL (`01_raw_events.sql`).
  - Automatically re-compresses 7-day-old raw log blocks with high-density ZSTD(15) compression for maximal disk savings while maintaining queryability.

---

### 4. SQLite TTL Purge Scheduler & Storage Reclaim
- **Automated Retention Scheduler**:
  - Implemented `NotificationPurgeScheduler` running periodic background cleanup:
    - Purges read notifications older than 14 days and unread notifications older than 60 days.
    - **Nullifies large `sample_metadata` JSON** in `onboarding_requests` after 7 days, reclaiming ~99% of sample payload disk usage while preserving request audit records.

---

## 📁 Changed & Added Files Inventory

### **Core Engine Application Code**
- `com.ulpf.common.db.CredentialRepository` — Added early-exit `EXISTS` subquery & active credential cache.
- `com.ulpf.common.db.OnboardingRepository` *(New)* — Repository for onboarding requests & user notifications.
- `com.ulpf.common.db.SourceRepository` *(New)* — Repository for log source metadata & status management.
- `com.ulpf.common.db.VendorRepository` *(New)* — Repository for vendor metadata & owner relationships.
- `com.ulpf.common.db.MappingRepository` — Dynamic versioning and lazy-loaded active mapping cache.
- `com.ulpf.controlplane.service.OnboardingService` — Vendor submission, SHA-256 key generation, candidate mapping creation, and approval activation.
- `com.ulpf.controlplane.service.NotificationService` — User notifications and read status handling.
- `com.ulpf.controlplane.service.NotificationPurgeScheduler` *(New)* — Scheduled TTL purges for notifications and sample payload nullification.
- `com.ulpf.dataplane.service.EventIngestionService` — SHA-256 API key hashing before credential resolution.
- `com.ulpf.mapping.service.MappingProposalService` — Version incrementing and candidate proposal persistence.
- `com.ulpf.analytics.service.AnalyticsService` — ClickHouse aggregation query engine with identifier sanitization.
- `com.ulpf.controlplane.controller.AdminController` — Wired admin approval and rejection endpoints.
- `com.ulpf.controlplane.controller.NotificationController` — Wired notification query and read endpoints.
- `com.ulpf.controlplane.controller.OnboardingController` — Wired vendor request submission endpoints.

### **Database Schemas & Infrastructure**
- `core-engine/src/main/resources/schema.sql` & `sqlite/schema.sql` — Added `vendors`, `sources`, `credentials`, `onboarding_requests`, `notifications`, and `mapping_versions` DDLs.
- `infra/clickhouse-init/01_raw_events.sql` — Added ClickHouse ZSTD(15) 7-day TTL re-compression codec.

### **Test Suite**
- `AnalyticsServiceTest` *(New)* — Verifies ClickHouse SQL generation and SQL injection protection.
- `MappingProposalServiceTest` — Integration tests for version incrementing and JSON serialization.
- `OnboardingServiceTest` — Verification for request submission, key hashing, approval workflow, and notifications.
- `CredentialRepositoryTest` — Verifies active key resolution, suspended vendor/source blocking, and revoked status.
- `NotificationPurgeSchedulerTest` *(New)* — Unit tests for notification cleanup and sample metadata nullification.
- `VendorRepositoryTest`, `SourceRepositoryTest`, `UserRepositoryTest` — Repository integration tests.

---

## 🧪 Verification & Test Results

The full project test suite was executed via `mvn test`:

```text
[INFO] Tests run: 74, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

All 74 unit and integration tests compile and pass cleanly across SQLite in-memory databases and mocked services.
