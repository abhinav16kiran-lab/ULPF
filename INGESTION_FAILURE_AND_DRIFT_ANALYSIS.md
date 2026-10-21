# Table Ingestion Failure & Schema Drift Notification System — Problem Analysis & Scenario Catalog

This document provides a comprehensive analysis of **Item 2 from the ULPF Engineering Backlog**: **Table Ingestion Failure & Drift Notification System**. It breaks down the core problem, why it happens in the current architecture, all operational scenarios where it manifests, and the required alerting and throttling design.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Root Cause: The "Silent Success" Architectural Decoupling](#2-root-cause-the-silent-success-architectural-decoupling)
3. [Deep Dive: Table Ingestion Failure Scenarios](#3-deep-dive-table-ingestion-failure-scenarios)
   - [Scenario 1: Dynamic Table Does Not Exist (`cols.isEmpty()`)](#scenario-1-dynamic-table-does-not-exist-colsisempty)
   - [Scenario 2: Missing or Null `target_table` in Mapping Metadata](#scenario-2-missing-or-null-target_table-in-mapping-metadata)
   - [Scenario 3: ClickHouse Dynamic Insert Exceptions & Batch Drop](#scenario-3-clickhouse-dynamic-insert-exceptions--batch-drop)
   - [Scenario 4: Column Type Incompatibility & Schema Rejection](#scenario-4-column-type-incompatibility--schema-rejection)
   - [Scenario 5: Active Credential with Inactive or Missing Mapping](#scenario-5-active-credential-with-inactive-or-missing-mapping)
4. [Deep Dive: Schema Drift Scenarios](#4-deep-dive-schema-drift-scenarios)
   - [Scenario 6: Partial Schema Drift (New Vendor Keys Appearing)](#scenario-6-partial-schema-drift-new-vendor-keys-appearing)
   - [Scenario 7: Total Schema Drift / Broken Mapping (All Fields Null)](#scenario-7-total-schema-drift--broken-mapping-all-fields-null)
   - [Scenario 8: Structural / Nested Payload Drift](#scenario-8-structural--nested-payload-drift)
   - [Scenario 9: High Unmapped Drift Ratio Exceeding Threshold](#scenario-9-high-unmapped-drift-ratio-exceeding-threshold)
5. [Impact Analysis: Why This is Dangerous in Production](#5-impact-analysis-why-this-is-dangerous-in-production)
6. [Design Blueprint: Alerting, Throttling & Remediation](#6-design-blueprint-alerting-throttling--remediation)
   - [Alert Deduplication & Throttling Strategy](#alert-deduplication--throttling-strategy)
   - [Actionable Alert Metadata Payload](#actionable-alert-metadata-payload)
   - [Integration with the Replay Engine](#integration-with-the-replay-engine)
7. [Implementation Roadmap](#7-implementation-roadmap)

---

## 1. Executive Summary

In ULPF, incoming logs undergo a **two-tiered ingestion process**:
1. **Tier 1 (Raw Archive)**: Ingests the raw vendor payload directly into `ulpf_raw.raw_events`.
2. **Tier 2 (Canonical Analytics)**: Normalizes vendor keys into canonical columns and writes them into dynamic analytics tables (`ulpf_events.events_{source}`).

### The Core Problem
When an event arrives, **Tier 1 almost always succeeds**, and the HTTP ingestion endpoint immediately returns `202 ACCEPTED` to the client. However, **Tier 2 can silently fail or degrade** in two critical ways:

1. **Table Ingestion Failure**: Events are written to raw storage, but **0 records are written to the dynamic analytical table**. The logs become completely invisible to dashboards, alerting engines, and SQL queries.
2. **Schema Drift Degradation**: Events are written to the dynamic table, but because the vendor modified their schema or added new keys, canonical fields are populated with `NULL`, while meaningful data is buried inside the compressed `raw_unmapped` column.

Currently, both conditions occur **silently** without notifying system administrators or the log source owner. This document categorizes every failure scenario and outlines the blueprint for the notification and drift detection system.

---

## 2. Root Cause: The "Silent Success" Architectural Decoupling

To understand why this problem occurs, we must trace the decoupled lifecycle between the HTTP ingress and the asynchronous database flush worker:

```
┌───────────────────────────────────────────────────────────────────────────────────────┐
│                      THE "SILENT SUCCESS" ARCHITECTURAL GAP                           │
└───────────────────────────────────────────────────────────────────────────────────────┘

 [ Vendor / Device ]
        │
        │ HTTP POST /v1/events
        ▼
 ┌─────────────────────────────────────────────────────────────────────────────────────┐
 │ EventIngestionService.java                                                          │
 │                                                                                     │
 │  1. Write to raw_events queue       ────────► [ Raw Queue ] ──► ulpf_raw.raw_events │
 │                                                                 (ALWAYS SUCCEEDS)   │
 │  2. Return HTTP 202 ACCEPTED to client                                              │
 │                                                                                     │
 │  3. Write to canonical queue   ────────► [ Canonical Queue ]                        │
 └───────────────────────────────────────────────────┬─────────────────────────────────┘
                                                     │ Asynchronous
                                                     ▼ Background Worker
                                     ┌───────────────────────────────┐
                                     │ ClickHouseIngestionRepository │
                                     │ .flushCanonical()             │
                                     └───────────────┬───────────────┘
                                                     │
                             ┌───────────────────────┴───────────────────────┐
                             │                                               │
                             ▼                                               ▼
                [ Target Table Missing /            [ Dynamic Batch Write Fails /    │
                  targetTable == null ]               Exception Thrown in Worker ]   │
                             │                                               │
                             ▼                                               ▼
                Silently Skipped & Dropped          Caught with log.warn(...)        │
                from Dynamic Queue                  and DISCARDED from Queue         │
                (0 Alerts Generated)                (0 Alerts Generated)             │
```

### Why Naive Monitoring Misses This:
- The vendor's log forwarder sees HTTP `202 ACCEPTED` with a valid `eventId` and assumes everything is functioning normally.
- ClickHouse raw metrics report healthy write throughput into `ulpf_raw.raw_events`.
- However, the analytical table `ulpf_events.events_{source}` receives **zero rows**, creating an undetected data blind spot.

---

## 3. Deep Dive: Table Ingestion Failure Scenarios

Below are all operational scenarios where log events are successfully persisted to raw storage but fail to reach the dynamic analytical table.

### Scenario 1: Dynamic Table Does Not Exist (`cols.isEmpty()`)
- **How it happens**:
  1. An admin approved a source, but the dynamic table creation DDL timed out or failed due to a transient ClickHouse glitch.
  2. Alternatively, a DBA or automated cleanup script dropped or renamed the table in ClickHouse.
  3. When `flushCanonical()` executes in `ClickHouseIngestionRepository`, it checks existing columns via `getColumnsForTable(tableName)`.
  4. If the table does not exist, ClickHouse returns an empty list.
- **Current Behavior**:
  - The worker logs a debug entry:
    `"Table ulpf_events.{} has no known columns or does not exist yet. Skipping flush of {} records."`
  - The entire batch of records is **silently skipped and discarded** from the canonical queue.
- **Consequence**:
  - Incoming logs accumulate in raw storage, but the analytics table remains permanently empty.
  - No one is alerted until an analyst notices that dashboards show no data.

---

### Scenario 2: Missing or Null `target_table` in Mapping Metadata
- **How it happens**:
  1. An active mapping exists for the source in SQLite, but the JSON metadata field `target_table` is missing, `null`, or an empty string.
  2. This occurs for sources onboarded prior to metadata serialization fixes or when an administrator manually edits the mapping JSON via an API without specifying `target_table`.
- **Current Behavior**:
  - `EventIngestionService` sets `targetTable = null`.
  - In `flushCanonical()`, records with `targetTable == null` are filtered out:
    `"Skipping dynamic table insert for event {} (no targetTable defined)"`
  - The records are never enqueued for dynamic table insertion.
- **Consequence**:
  - 100% of logs bypass dynamic storage indefinitely without generating any warning or error.

---

### Scenario 3: ClickHouse Dynamic Insert Exceptions & Batch Drop
- **How it happens**:
  1. The canonical flush worker formats a bulk SQL insert for `ulpf_events.{tableName}` and calls `clickhouseJdbcTemplate.batchUpdate(...)`.
  2. ClickHouse throws a runtime exception due to:
     - Memory pressure limit exceeded (`Memory limit for query exceeded`).
     - Transient network socket timeout or connection pool exhaustion.
     - Database disk I/O bottleneck.
- **Current Behavior**:
  - In the raw flush worker (`flush()`), failed raw events are safely pushed back into an in-memory retry queue.
  - In the canonical worker (`flushCanonical()`), the exception is caught in a `try/catch` block, logged at `WARN` level, and **the batch is completely discarded**:
    ```
    Failed to flush dynamic events to ulpf_events.{}: <exception message>
    ```
- **Consequence**:
  - Dynamic records are dropped from memory.
  - While raw logs remain safe in `raw_events`, the analytical table suffers permanent data gaps unless manually replayed.

---

### Scenario 4: Column Type Incompatibility & Schema Rejection
- **How it happens**:
  1. A source was originally approved with a canonical mapping defining a numeric field (e.g. `latency_ms` mapped as a number or a typed column).
  2. The vendor begins emitting string values or malformed data (e.g. `latency_ms: "N/A"`, `"null"`, or `"--"`).
  3. The batch insert prepared statement fails when binding or when ClickHouse rejects the type coercion.
- **Current Behavior**:
  - The entire batch of events containing the offending record fails to insert.
  - The error is logged to application logs, but neither the vendor nor the administrator is alerted to fix the vendor emission or update the mapping.

---

### Scenario 5: Active Credential with Inactive or Missing Mapping
- **How it happens**:
  1. An administrative action or migration updates source credentials or activates an API key, but the corresponding mapping version is in `RETIRED` or `CANDIDATE` status, or was deleted.
  2. When logs arrive, `mappingRepository.findActiveBySourceId(sourceId)` returns `Optional.empty()`.
- **Current Behavior**:
  - The raw event is recorded with `mapping_version = NULL`.
  - The event is skipped from dynamic table insertion because there is no target table or mapping rules.
- **Consequence**:
  - Vendor streams data continuously, believing their credentials are functioning, but zero events reach analytical storage.

---

## 4. Deep Dive: Schema Drift Scenarios

Schema drift occurs when vendor log formats evolve over time without an updated canonical mapping. Unlike ingestion failures (where records don't write at all), schema drift allows records to write to the dynamic table, but **critical fields fail to map**, rendering logs unsearchable by standard column queries.

---

### Scenario 6: Partial Schema Drift (New Vendor Keys Appearing)
- **How it happens**:
  - A vendor upgrades firewall firmware from v1.2 to v2.0.
  - The new firmware begins emitting additional security attributes: `threat_severity`, `rule_uuid`, and `geo_country`.
  - The active mapping in ULPF only maps the old fields (`src_ip`, `dst_port`, `action`).
- **Current Behavior**:
  - `MappingTransformer` extracts the old mapped fields and inserts them into canonical columns.
  - The new fields are routed to `raw_unmapped String CODEC(ZSTD(1))`.
- **Consequence**:
  - Security analysts attempting to query `SELECT * FROM ulpf_events.events_firewall WHERE threat_severity = 'CRITICAL'` find that the column does not exist or remains empty.
  - Critical security indicators remain buried inside compressed JSON strings.

---

### Scenario 7: Total Schema Drift / Broken Mapping (All Fields Null)
- **How it happens**:
  - A vendor changes their logging agent configuration or switches payload formats (e.g., from custom JSON key `source_ip` to OpenTelemetry standard `client.address`).
  - None of the vendor's incoming payload keys match the vendor keys registered in the active mapping.
- **Current Behavior**:
  - `MappingTransformer.extractMappedFields()` produces an empty map `{}`.
  - `MappingTransformer.extractUnmappedFields()` captures 100% of the payload.
  - A row is inserted into `ulpf_events.{source}` where every canonical column is `NULL`, and the entire payload sits in `raw_unmapped`.
- **Consequence**:
  - High storage overhead with zero analytical value.
  - Analytical metrics, count aggregations by IP, and alerting rules silently break because all columns evaluate to `NULL`.

---

### Scenario 8: Structural / Nested Payload Drift
- **How it happens**:
  - Vendor wraps log attributes inside a nested object (e.g., changing from flat `{"src_ip": "10.0.0.1", "action": "ALLOW"}` to nested `{"event": {"network": {"src_ip": "10.0.0.1"}}, "action": "ALLOW"}`).
  - The current mapping engine evaluates top-level vendor keys.
- **Current Behavior**:
  - Top-level keys like `event` are treated as unmapped JSON objects and written to `raw_unmapped`.
  - Canonical columns remain `NULL`.

---

### Scenario 9: High Unmapped Drift Ratio Exceeding Threshold
- **How it happens**:
  - Over time, a source emits 20 different attributes, but only 4 are mapped (an unmapped ratio of 80%).
- **Consequence**:
  - The platform wastes disk space on unindexed strings and misses valuable analytical insights.
  - The platform administrator needs proactive notification when the unmapped attribute ratio exceeds a defined threshold (e.g., $> 30\%$).

---

## 5. Impact Analysis: Why This is Dangerous in Production

| Risk Dimension | Operational Reality |
| :--- | :--- |
| **Silent Data Blind Spots** | Security teams rely on dynamic tables for real-time threat detection (SIEM). If an ingestion failure occurs, alerts stop firing because the SIEM table receives 0 events, while the vendor believes everything is normal. |
| **Delayed Problem Discovery** | Days or weeks often pass before an analyst notices missing data in a dashboard, by which time gigabytes of historical logs require complex backfilling. |
| **Alert Fatigue vs. Alert Blindness** | High-throughput streams process 1,000 to 50,000 events per second. If an ingestion failure generates an alert on every dropped event, it creates an **alert storm** (millions of notifications) that crashes the notification database. |
| **Vendor-Admin Coordination Friction** | Administrators do not know which vendor to contact, and vendors do not know their format changed without clear metadata (vendor ID, source ID, rejected keys). |

---

## 6. Design Blueprint: Alerting, Throttling & Remediation

To solve these problems cleanly without degrading high-throughput ingestion performance, the system requires four coordinated components:

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                       NOTIFICATION & DRIFT ARCHITECTURE                                 │
└─────────────────────────────────────────────────────────────────────────────────────────┘

  [ Event Ingestion / Flush Worker ]
                 │
                 ├──► Ingestion Failure Detected (table missing / insert failed)
                 │    OR
                 └──► Schema Drift Detected (unmapped ratio > 30%)
                             │
                             ▼
                 ┌───────────────────────────────────────┐
                 │    Alert Throttling & Dedup Cache     │
                 │  (Sliding Window: 1 Alert / 30 mins)  │
                 └───────────────────┬───────────────────┘
                                     │ Allowed (Not Throttled)
                                     ▼
                 ┌───────────────────────────────────────┐
                 │          NotificationService          │
                 └───────────┬───────────────────────┬───┘
                             │                       │
                             ▼                       ▼
                     [ Admin In-App /        [ Vendor In-App /
                       Email Alert ]           Webhook Alert ]
                             │                       │
                             └───────────┬───────────┘
                                         ▼
                     ┌───────────────────────────────────────┐
                     │          Remediation Flow             │
                     │  1. Vendor/Admin Updates Mapping      │
                     │  2. Schema Provisioning Applied       │
                     │  3. Replay Engine Triggered to        │
                     │     Backfill Missing Dynamic Rows     │
                     └───────────────────────────────────────┘
```

---

### Alert Deduplication & Throttling Strategy
To prevent alert storms on high-throughput streams:
1. **Composite Throttling Key**: Maintain an in-memory cache keyed by:
   `sourceId + ":" + failureType` (e.g., `"src_firewall_alpha:TABLE_MISSING"` or `"src_web_app:HIGH_SCHEMA_DRIFT"`).
2. **Time-Based Window**: Suppress duplicate alerts for the same source and failure type for **30 minutes**.
3. **Accumulator Counter**: While throttled, increment a counter tracking the number of dropped or drifted events during the suppression window.
4. **Digest / Recovery Notification**: When the error condition resolves or when the window expires, send an update stating:
   `"Source 'Firewall Alpha' has experienced 42,500 unmapped events in the last 30 minutes."`

---

### Actionable Alert Metadata Payload
Alerts must not be vague. Every notification generated by the system should include:

| Attribute | Example Value | Description |
| :--- | :--- | :--- |
| **Notification Type** | `INGESTION_FAILURE` or `SCHEMA_DRIFT` | Distinguishes between zero writes vs. partial field drift. |
| **Source ID & Name** | `src_firewall_alpha` ("Firewall Alpha") | Identifies the impacted log source. |
| **Vendor ID & Owner** | `vnd_paloalto` (Owner User: `usr_sec_admin`) | Routes alert to the responsible vendor. |
| **Target Dynamic Table** | `ulpf_events.events_firewall_alpha` | Identifies which table is missing or failing. |
| **Error Reason** | `TABLE_DOES_NOT_EXIST` or `CLICKHOUSE_TIMEOUT` | Precise root cause. |
| **Drift Metrics** | `Unmapped Keys: [threat_severity, rule_uuid]` | Exact vendor keys that need mapping. |
| **Affected Event Count**| `12,450 events dropped from dynamic queue` | Quantifies the scale of data impact. |
| **Recommended Action** | *"Trigger replay via POST /v1/admin/sources/{id}/replay"* | Actionable next step. |

---

### Integration with the Replay Engine
This notification system directly completes the lifecycle with our newly merged **Replay Feature**:
1. When an ingestion failure alert is raised, raw logs continue accumulating safely in `ulpf_raw.raw_events`.
2. The admin or vendor fixes the underlying issue (e.g., re-provisions the table or approves an updated mapping schema).
3. The notification alert provides a one-click or API command to trigger [`ReplayBackfillService`](file:///home/abhinav/Desktop/ULPF/core-engine/src/main/java/com/ulpf/replay/service/ReplayBackfillService.java):
   ```http
   POST /v1/admin/sources/{sourceId}/replay
   ```
4. The replay engine reads the historical events from raw storage and backfills the dynamic table, completely erasing the data gap.

---

## 7. Implementation Roadmap & Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                      INGESTION FAILURE & DRIFT SYSTEM ARCHITECTURE                      │
└─────────────────────────────────────────────────────────────────────────────────────────┘

   Incoming Event Stream (HTTP /syslog)
               │
               ▼
   ┌───────────────────────────────────────────────────────────────┐
   │ EventIngestionService.java                                    │
   │  • Check mapping completeness:                                │
   │      driftRatio = unmappedKeys / totalKeys                    │
   │  • If driftRatio > 30% OR 100% fields NULL:                   │
   │      ──► IngestionAlertService.recordSchemaDrift(...)         │
   │  • Fallback target_table: constructTableName(sourceName)      │
   └───────────────┬───────────────────────────────────────────────┘
                   │ Enqueue Canonical
                   ▼
   ┌───────────────────────────────────────────────────────────────┐
   │ ClickHouseIngestionRepository.flushCanonical()                │
   │  • If cols.isEmpty() (Table Missing):                         │
   │      ──► IngestionAlertService.recordTableMissing(...)        │
   │  • If catch (Exception e) (Insert Failed):                    │
   │      ──► IngestionAlertService.recordInsertFailure(...)       │
   └───────────────┬───────────────────────────────────────────────┘
                   │
                   ▼
   ┌───────────────────────────────────────────────────────────────┐
   │ IngestionAlertService.java (New Component)                    │
   │  • In-Memory Sliding Window (30-Minute Quiet Window)          │
   │  • Lock Key: (sourceId + ":" + failureType)                   │
   │  • Accumulator: Counts dropped events during quiet window     │
   │  • Routes Notifications:                                      │
   │      - System Admins (via UserRepository)                     │
   │      - Source Vendor Owner (via VendorRepository)             │
   └───────────────┬───────────────────────────────────────────────┘
                   │
                   ▼
   ┌───────────────────────────────────────────────────────────────┐
   │ NotificationService.java                                      │
   │  • Stores notification in SQLite                              │
   │  • Displays in UI Console + Webhook Alert                     │
   │  • Includes Actionable Command:                               │
   │      "POST /v1/admin/sources/{id}/replay to backfill"         │
   └───────────────────────────────────────────────────────────────┘
```

---

## 8. Final Engineering Implementation Plan

### 8.1 Component Breakdown & Class Design

The system is implemented as specialized components working in tandem:

#### 1. `TableIngestionFailureNotificationService`
* **Package**: `com.ulpf.dataplane.service`
* **Responsibilities**:
  * Detecting table write failures, unprovisioned tables, missing target tables, unapproved mappings, and missing table columns.
  * In-memory sliding-window throttling (default: 30 minutes, `ulpf.table-failure.cooldown-ms`) keyed by `sourceId:failureType`.
  * Race-condition-free evaluation using `ConcurrentHashMap.compute()` returning atomic `EvaluationResult` without timestamp race conditions.
  * Atomic event drop accumulator (`accumulatedCount` and `initialAlertCount`).
  * Periodic background digest delivery (`@Scheduled(fixedRate = 60000)` via `flushExpiredDigests()`) dispatched to both admins and vendor owners even if failures stop.
  * Ingestion incident lifecycle tracking: opens incident on first alert of a window, updates counts at digest time, and resolves incident on recovery.
* **Public Methods**:
  * `notifyTableMissing(String sourceId, String tableName, int eventCount)`
  * `notifyInsertFailure(String sourceId, String tableName, int eventCount, String errorMessage)`
  * `notifyNoTargetTable(String sourceId, int eventCount)` (Scenario 2: mapping exists, but target_table is null)
  * `notifyMissingMapping(String sourceId, int eventCount)` (Scenario 5: source emits events without approved mapping)
  * `notifyMissingColumns(String sourceId, String tableName, Set<String> missingColumns, int eventCount)`
  * `markRecovered(String sourceId, String tableName)` (Resolves incident on successful flush and dispatches recovery notification with replay window)
  * `flushExpiredDigests()` (Scheduled runner every 60s)

#### 2. `SchemaDriftNotificationService`
* **Package**: `com.ulpf.dataplane.service`
* **Responsibilities**:
  * Detecting live log schema drift, total drift (100% unmapped keys), and high drift ratio (>30%).
  * **30-Minute Sliding Cooldown Window** (`ulpf.schema-drift.cooldown-ms`, default: 1,800,000 ms, updated from 1 hour).
  * Keyed per source: `sourceId:SCHEMA_DRIFT` (and `sourceId:TOTAL_DRIFT` for immediate critical escalation).
  * `DriftState` tracking `firstSeen`, `lastSeen`, `eventCount`, and a set of accumulated unmapped keys (capped at 50).
  * Scheduled minute-interval digest flush (`flushExpiredDigests()`) summarizing drift counts and new keys over the 30-minute window.
* **Public Methods**:
  * `checkAndNotifyDrift(String sourceId, Set<String> unmappedKeys, int totalKeys, boolean isTotalDrift)`
  * `notifyHistoricalFallback(...)`
  * `flushExpiredDigests()` (Scheduled runner every 60s)

#### 3. `AlertDispatcher` & Webhook Subsystem
* **Package**: `com.ulpf.dataplane.service`
* **Responsibilities**:
  * Shared alert delivery hub used by both `TableIngestionFailureNotificationService` and `SchemaDriftNotificationService`.
  * Resolves administrative users (`UserRepository`) and source vendor owners (`VendorRepository`).
  * Dual-delivery routing: internal in-app notifications (`NotificationService` / `OnboardingRepository`) and external HTTP webhooks.
  * **Jackson JSON Serialization**: Uses Jackson `ObjectMapper` for well-formed JSON payloads with quotes, special characters, and newlines properly escaped.
  * **HMAC-SHA256 Webhook Signatures**: Computes HMAC-SHA256 signature using `ulpf.alert.webhook-secret` (`ULPF_ALERT_WEBHOOK_SECRET`) and transmits it in the `X-ULPF-Signature` header.
  * Non-blocking, resilient HTTP client with 3-second timeout and 1 automatic retry.

#### 4. `IngestionIncidentRepository` & Persistent Incident Tracking
* **Package**: `com.ulpf.common.db`
* **Table**: `ingestion_alert_incidents` (defined in `schema.sql` and `sqlite/schema.sql`):
  ```sql
  CREATE TABLE IF NOT EXISTS ingestion_alert_incidents (
      incident_id     TEXT PRIMARY KEY,
      source_id       TEXT NOT NULL,
      failure_type    TEXT NOT NULL,
      table_name      TEXT,
      started_at      TEXT NOT NULL,
      last_seen_at    TEXT NOT NULL,
      resolved_at     TEXT,
      affected_count  INTEGER NOT NULL DEFAULT 0,
      status          TEXT NOT NULL DEFAULT 'OPEN',
      details         TEXT
  );
  ```
* **Lifecycle & Write Policy**:
  * Incident opened on the **first alert of a window** (`status = 'OPEN'`).
  * Counts and `last_seen_at` updated at window expiry / digest time (zero DB writes per event).
  * Incident resolved (`status = 'RESOLVED'`, `resolved_at` recorded) when ClickHouse ingestion succeeds.
  * Loaded into memory on startup (`@PostConstruct`) so recovery tracking survives service restarts.

#### 5. Integration Points in Existing Code

| Target File | Change Description |
| :--- | :--- |
| **`ClickHouseIngestionRepository.java`** | In `flushCanonical()`: <br>1. **Cache Fix**: `getColumnsForTable()` does not cache empty column lists, preventing permanent stale cache when tables are provisioned.<br>2. **Fallback**: When `rec.targetTable()` is null, if mapping exists, checks candidate table `constructTableName()` before alerting.<br>3. **Alerts**: Dispatches `notifyNoTargetTable`, `notifyMissingMapping`, `notifyTableMissing`, `notifyInsertFailure`, and `notifyMissingColumns`.<br>4. **Dynamic Column Validation & Cache**: Validates dynamic column names against `^[a-z_][a-z0-9_]*$` before ALTER, and caches failed ALTERs for 10 minutes to prevent repetitive failed ALTER attempts.<br>5. **Recovery Hook**: On successful batch insert, invokes `tableFailureNotificationService.markRecovered(sourceId, tableName)`. |
| **`EventIngestionService.java`** | In `extractUnmappedFields()`: Computes total payload keys and evaluates `isTotalDrift` and drift percentage, passing them to `schemaDriftNotificationService.checkAndNotifyDrift()`. **Skips drift alerting when source has no active mapping** (`mappingOpt.isEmpty()`) to prevent redundant alerts alongside `notifyMissingMapping`. |
| **`DynamicSchemaProvisioningService.java`** | Exposes `addColumnIfNotExists(tableName, columnName, columnType)` and invalidates ClickHouse column cache upon table creation or alteration. |

#### 6. Automatic Remediation & Replay Workflow
When an incident recovers:
1. **Exact Timestamp Window**: The recovery notification provides the true start time of the incident (`started_at`) and resolution time (`resolved_at`) formatted in server `LocalDateTime`.
2. **Ready-to-Execute Replay Command**:
   - The notification provides an actionable replay call:
     ```http
     POST /v1/admin/sources/{sourceId}/replay
     {
       "fromTime": "2026-10-21T10:15:30.123",
       "toTime": "2026-10-21T10:45:00.456"
     }
     ```
3. **Execution**: The `ReplayBackfillService` streams the gap events from `ulpf_raw.raw_events`, applies the updated mapping, and populates the dynamic table without data loss.

---

### 8.2 Testing Strategy & Test Suites

The test suite contains 277 automated tests verifying every aspect of alerting, throttling, persistence, and recovery:

1. **`TableIngestionFailureNotificationServiceTest.java`** (14 tests):
   - `testAlertThrottlingSuppressesRapidDuplicateNotifications`: Rapid calls result in exactly 1 alert, accumulating suppressed events.
   - `testIndependentThrottlingPerSourceAndFailureType`: Independent throttling across sources and failure types.
   - `testDigestDispatchedWhenWindowExpires`: Digest delivery after window expiry.
   - `testNotifyMissingMapping_DispatchesExpectedAlerts`: Scenario 5 missing mapping alert.
   - `testNotifyMissingColumns_DispatchesExpectedAlerts`: Missing required columns alert.
   - `testScheduledDigestDispatchesForExpiredWindowWithoutNewEvents`: Periodic scheduled digest worker dispatch.
   - `testConcurrent32ThreadsSameSourceAndType_ExactlyOneAlertAndTotalCountPreserved`: 32 concurrent threads $\times$ 1,000 calls race condition verification.
   - `testTinyCooldownWithFlushLoop_NoLostCounts`: Continuous background flusher with rapid calls ensures zero lost counts.
   - `testFailureThenSuccess_IncidentLifecycleAndRecoveryReplayMessage`: Full lifecycle (failure $\rightarrow$ incident OPEN $\rightarrow$ recovery $\rightarrow$ incident RESOLVED with ready replay window).
   - `testSuccessWithNoOpenIncident_NothingSent`: Zero overhead and no notifications sent when no open incident exists.
   - `testTwoFailureWindows_CreatesTwoIncidents`: Successive failure windows create distinct incidents.

2. **`SchemaDriftNotificationServiceTest.java`** (13 tests):
   - `testTotalSchemaDriftDispatchesCriticalAlert`: 100% unmapped fields critical alert.
   - `testHighSchemaDriftRatioAlert`: High drift ratio (>30%) alert.
   - `testDuplicateSchemaDriftSuppressedWithinCooldownWindow`: Thread-safe duplicate suppression with AtomicBoolean.
   - `testDistinctUnmappedKeysThrottledTogetherWithinWindow`: Drift keys within 30-min window added to digest rather than spamming alerts.
   - `testRapid10kConcurrentDriftCallsExactlyOneNotification`: 10,000 rapid calls produce exactly 1 notification.
   - `testDriftThenTotalDriftEscalatesImmediately`: Escalation from partial drift to total drift alerts immediately.
   - `testWindowExpiryWithNoNewEventsScheduledFlushSendsDigest`: Scheduled digest flush sends summary with accumulated keys.
   - `testBoundaryDriftThreshold3Of10NormalVersus4Of10HighDrift`: Boundary verification (3/10 normal vs 4/10 high drift).
   - `testRoutingThroughNotificationService`: Routes via `NotificationService` when injected.

3. **`AlertDispatcherTest.java`** (4 tests):
   - Webhook delivery using JDK `HttpServer`.
   - Valid JSON payload formatting with special character escaping.
   - HMAC-SHA256 signature verification in `X-ULPF-Signature` header.
   - Dead/failing webhook resilience (non-blocking, never throws).

4. **`IngestionIncidentRepositoryTest.java`** (5 tests):
   - Incident record CRUD operations.
   - Status transitions (`OPEN` $\rightarrow$ `RESOLVED`).
   - Querying open incidents by source, type, and system-wide.

5. **`EventIngestionServiceDriftTest.java`** (6 tests):
   - Ingestion-level drift detection (10 keys / 2 mapped $\rightarrow$ 8 unmapped, total 10).
   - All keys unmapped $\rightarrow$ `isTotalDrift = true`.
   - All keys mapped $\rightarrow$ drift service never called.
   - Exactly 30% unmapped boundary condition.
   - Nested payload (`event.network.src_ip`) top-level drift.
   - Missing active mapping $\rightarrow$ drift service not called.

6. **`ClickHouseIngestionRepositoryTest.java`** (13 tests):
   - `testFlushCanonical_MissingTargetTable_TriggersNotifyNoTargetTable`: Alert on missing target table.
   - `testFlushCanonical_MissingTable_TriggersNotifyTableMissing`: Alert on unprovisioned ClickHouse table.
   - `testFlushCanonical_BatchInsertException_TriggersNotifyInsertFailure`: Alert on batch insert exception.
   - `testFlushCanonical_MissingMapping_TriggersNotifyMissingMapping`: Alert on unmapped stream.
   - `testFlushCanonical_MissingColumns_ReconcilesViaDynamicSchemaProvisioningService`: Column addition when ALTER succeeds.
   - `testFlushCanonical_FailedAlter_TriggersNotifyMissingColumns`: Alert when ALTER returns false.
   - `testFlushCanonical_InvalidColumnName_RejectsAlterAndTriggersAlert`: Regex validation rejection.
   - `testFlushCanonical_FailedColumnCached_AlterAttemptedOnlyOnce`: 10-minute failed column cache.
   - `testFlushCanonical_Success_NoFailureNotification`: Verifies `markRecovered` is invoked on success.



