# Unified Log Processing Framework (ULPF) — Replay & Backfill Engine

This document provides a comprehensive, easy-to-read explanation of the **Replay & Backfill Feature** in the Unified Log Processing Framework (ULPF). It details the core concept, operational scenarios, automation workflows, the role and trade-offs of Bloom filters, data integrity guarantees (ensuring zero log loss), and production reliability design.

---

## Table of Contents

1. [Executive Summary: What is the Replay Feature?](#1-executive-summary-what-is-the-replay-feature)
2. [Underlying Storage Architecture: Raw vs. Dynamic](#2-underlying-storage-architecture-raw-vs-dynamic)
3. [Scenarios: Where is Replay Required vs. Not Required?](#3-scenarios-where-is-replay-required-vs-not-required)
4. [Automation Workflow: How Replay Operates](#4-automation-workflow-how-replay-operates)
5. [The Bloom Filter Deep Dive: Where, How, and Why](#5-the-bloom-filter-deep-dive-where-how-and-why)
6. [Data Integrity Analysis: Can Any Logs Ever Get Missed?](#6-data-integrity-analysis-can-any-logs-ever-get-missed)
7. [Deduplication & Concurrency Architecture](#7-deduplication--concurrency-architecture)
8. [Failure Recovery, Resumability & Memory Pressure Throttling](#8-failure-recovery-resumability--memory-pressure-throttling)
9. [REST API & Administrative Controls](#9-rest-api--administrative-controls)
10. [Summary Matrix & Key Takeaways](#10-summary-matrix--key-takeaways)

---

## 1. Executive Summary: What is the Replay Feature?

In ULPF, log streams originate from diverse vendor systems and edge devices (such as firewalls, application servers, authentication gateways, and IoT sensors). Each source sends log events formatted according to its own proprietary schema.

ULPF uses a **two-tiered ingestion model**:
1. **Tier 1 (Raw Ingestion Layer)**: Immediately stores every incoming log payload in an immutable, append-only raw archive table (`ulpf_raw.raw_events`). This ensures that no data is ever lost, even before parsing or schema validation occurs.
2. **Tier 2 (Canonical Analytics Layer)**: Parses vendor fields, normalizes them into standardized canonical columns (such as source IP, destination port, user ID, status code), and writes the structured records into source-specific dynamic tables (`ulpf_events.events_{source_name}`).

### The Problem
When a vendor first registers a log source, or while an onboarding request is pending administrative review:
- The schema mapping is still in a draft or candidate status.
- The dedicated analytics table in ClickHouse has not been created yet.
- Incoming logs can only be saved to the raw archive table (`ulpf_raw.raw_events`).

As a result, all historical logs received before approval sit unparsed in the raw table, while the analytical table remains empty.

### The Solution: Replay Engine
The **Replay Feature** is an automated background service that bridges this gap:
1. It reads historical raw events for the approved source from the raw archive.
2. It applies the newly approved canonical mapping to translate vendor-specific fields into standard canonical fields.
3. It ensures the destination analytical table is provisioned and ready.
4. It eliminates any potential duplicate entries.
5. It batch-inserts the normalized rows into the analytics table, making past logs immediately queryable, filterable, and visible on administrative dashboards alongside live streams.

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                               ULPF INGESTION & REPLAY ARCHITECTURE                     │
└─────────────────────────────────────────────────────────────────────────────────────────┘

  [ Log Source / Vendor ]
             │
             │ Real-Time Ingest (HTTP)
             ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │                        Event Ingestion Service                        │
  └──────────────────┬─────────────────────────────────┬──────────────────┘
                     │                                 │
           Always Writes (Raw)               Only If Active Mapping Exists
                     │                                 │
                     ▼                                 ▼
         ┌───────────────────────┐         ┌─────────────────────────┐
         │  ulpf_raw.raw_events  │         │ ulpf_events.events_src  │
         │ (ZSTD, Immutable Log) │         │   (Canonical Tables)    │
         └───────────┬───────────┘         └────────────▲────────────┘
                     │                                  │
                     │  1. Read Historical Raw Chunks   │  3. Batch Insert
                     │     via (timestamp, event_id)    │     Transformed Events
                     ▼                                  │
         ┌──────────────────────────────────────────────┴──────────┐
         │                Replay / Backfill Service                │
         │  • Dynamic Table Verification & Provisioning            │
         │  • Schema Mapping Transformation (Vendor -> Canonical)  │
         │  • Exact Batch Deduplication Check                      │
         │  • Adaptive ClickHouse Memory Throttling                │
         └─────────────────────────────────────────────────────────┘
```

---

## 2. Underlying Storage Architecture: Raw vs. Dynamic

ULPF separates raw storage from structured analytics to maximize both data safety and query performance:

### 1. `ulpf_raw.raw_events` (The Cold/Warm Immutable Archive)
- **Role**: Serves as the immutable event store and audit trail for all incoming data.
- **Storage Engine**: ClickHouse `MergeTree`, partitioned monthly by received date and sorted by vendor, source, arrival time, and unique event ID.
- **Payload Storage**: Stores the entire original payload in a compressed string column using Zstandard (ZSTD) compression.
- **Automated Recompression**: Older data (past 7 days) is automatically recompressed using high-ratio Zstandard compression (saving roughly 80% disk space) without deleting any rows.
- **Search Optimization**: Includes a built-in ClickHouse token Bloom filter index over raw payloads, allowing fast keyword searches without full-table decompression.

### 2. `ulpf_events.events_{source_name}` (The Hot Analytical Dynamic Tables)
- **Role**: Powers analytical dashboards, metric aggregations, alerting, and fast filtering.
- **Storage Engine**: Dynamically provisioned ClickHouse `MergeTree` tables created on demand when a source is approved.
- **Structure**: Includes common metadata columns (event ID, lineage tracking, vendor ID, source ID, timestamp), typed columns for all mapped attributes (such as IP addresses, ports, protocol names, latency), and a compressed unmapped column containing any vendor fields not covered by the mapping.

---

## 3. Scenarios: Where is Replay Required vs. Not Required?

The replay engine is designed around distinct operational scenarios. Some strictly require replay, while others specifically do not or handle updates via alternative mechanisms.

| Scenario | Description | Replay Required? | Action Taken |
| :--- | :--- | :---: | :--- |
| **Scenario 1: First-Time Onboarding Approval** | Vendor onboarded; sample or historical events ingested into raw storage before admin review. Admin reviews and approves source. Dynamic table was just created. | **YES (Mandatory)** | **Automated Replay**: Streams all historical raw records for the source, applies the approved mapping, and populates the analytics table. |
| **Scenario 2: Extended Admin Review Delay** | Days or weeks elapsed between source registration and approval. Millions of events accumulated in raw storage with no mapping version. | **YES** | Replay processes raw records across the entire historical window using cursor pagination in bounded chunks. |
| **Scenario 3: ClickHouse Dynamic Insert Failure** | During live streaming, an analytical table insert failed (network timeout, temporary overload), but raw storage write succeeded. | **YES (Recovery)** | Admin or auto-recovery invokes replay over the failure time window. Because raw storage is complete, replay reconstructs the missing analytical records. |
| **Scenario 4: Target Table Missing or Dropped** | Dynamic table was accidentally dropped or table provisioning failed due to a transient database restart. | **YES** | Replay verifies table existence, automatically re-creates the table schema via dynamic DDL, and repopulates all records. |
| **Scenario 5: Schema Update (Mapping Version 2 Approved)** | Source is already active. Vendor updates mapping to add new fields. Admin approves the update. | **NO (Replay Not Required)** | Schema updates execute column additions (`ALTER TABLE ADD COLUMN`). The mapping switch in SQLite is atomic (sub-millisecond). Replay is **not** run automatically to prevent unnecessary re-processing of already canonicalized logs. |
| **Scenario 6: Sensor Telemetry Divergence** | Edge sensor logs evaluated with delta thresholds where readings did not change significantly. The event was intentionally written *only* to raw storage. | **NO (Intentional Non-Replay)** | Suppressed sensor readings are intentionally omitted from analytics tables to save storage. Replay preserves this design and does not force suppressed readings into dynamic tables. |
| **Scenario 7: Empty Source (Zero Historical Logs)** | Admin approves a source or triggers replay on a source that hasn't ingested any raw logs yet. | **NO-OP (Immediate Exit)** | The service queries the count of raw events. If the count is zero, it immediately marks the job complete and notifies the administrator. |
| **Scenario 8: Concurrent Live Ingestion During Replay** | Replay is running over historical data while live logs continue to stream into the same dynamic table. | **YES (Handled Safely)** | Replay bounds its query window to events received up to the job start time and verifies existing IDs to guarantee zero collisions with live incoming logs. |

---

## 4. Automation Workflow: How Replay Operates

Replay is fully integrated into both the **automated control plane lifecycle** and the **administrative operations console**.

### A. Automated Trigger on Admin Approval
When an administrator reviews and approves an onboarding request in the admin console:
1. **Schema & Mapping Activation**: The candidate mapping is marked active, and the dynamic ClickHouse table is provisioned.
2. **Source Activation**: The log source status transitions to active.
3. **Credential Activation (Security Gate)**: The vendor's API credentials are activated **last**, strictly after tables and schemas are ready. This guarantees that live incoming events cannot arrive before the system is prepared to process them.
4. **Background Replay Initiation**: If the request is for a new source (not a minor schema update), the onboarding service automatically launches an asynchronous replay job in the background without requiring manual administrator intervention.

### B. Execution Loop in Background Worker
The background worker executes the backfill in controlled, isolated steps:

1. **Validation**: Confirms the source exists, the mapping is active, and the dynamic table exists (or auto-provisions it if missing).
2. **Estimation**: Counts total matching historical records to establish a baseline for progress tracking.
3. **Chunked Streaming**: Reads raw events in configurable batches (default 1,000 events) using timestamp and event ID pagination to prevent duplicate scans.
4. **Health Check (Adaptive Throttling)**: Checks ClickHouse memory pressure before each batch; pauses briefly if database memory utilization exceeds safe thresholds.
5. **Exact Deduplication**: Queries the analytical table to identify which event IDs in the current batch already exist, filtering them out to prevent duplicate inserts.
6. **Canonical Transformation**: Translates vendor fields into standard canonical fields, packaging any unmapped attributes into the unmapped JSON column.
7. **Batch Insertion**: Inserts the transformed canonical batch into the dynamic table in a single bulk operation.
8. **Cursor Progression**: Advances the pagination cursor to the timestamp and ID of the last processed event.
9. **Completion & Notification**: Once all chunks are processed (or if an administrator cancels the job), updates job status and dispatches a completion alert to the source owner and admin.

---

## 5. The Bloom Filter Deep Dive: Where, How, and Why

There is often confusion around whether a Bloom filter is used, where it is used, and why certain architectural decisions were made. Here is the technical explanation:

### 1. ClickHouse Disk Storage Layer: YES, Bloom Filter is Active
ClickHouse uses a native token Bloom filter index directly on disk for the raw payload column in `ulpf_raw.raw_events`.

- **How it works**: A Bloom filter is a compact mathematical structure that can definitively tell if an element is **not** present, or if it **might** be present. ClickHouse builds token Bloom filter hashes for each block of 8,192 log records.
- **Why it matters**: When an analyst runs substring searches, keyword lookups, or regex filters across millions of raw logs, ClickHouse checks the Bloom filter first. If the filter indicates the keyword does not exist in a block, ClickHouse completely skips reading and decompressing that data from disk. This provides 10x to 50x faster analytical search performance over raw data.

---

### 2. Replay Deduplication Layer: Evaluated, Then Replaced with Exact SQL
During initial architectural design, an **in-memory Bloom filter** was considered for holding dynamic table event IDs in application memory to perform fast deduplication checks during replay.

However, detailed architectural evaluation revealed critical drawbacks, leading to the adoption of **chunked batched SQL checks** instead:

#### Why the In-Memory Bloom Filter Was Replaced:

1. **Risk of Dropping Legitimate Logs (False Positives):**
   - Bloom filters are probabilistic: they never produce false negatives, but they can produce **false positives** (reporting that an item exists when it actually does not).
   - If an in-memory Bloom filter has even a 0.1% false positive rate, **1 out of every 1,000 valid historical logs would be incorrectly marked as "already exists" and skipped**.
   - For an enterprise log platform, silently dropping hundreds or thousands of logs during replay is unacceptable.

2. **The New Source Reality (Empty Tables):**
   - In most replay scenarios (first-time source onboarding), the destination table was created seconds earlier and contains **zero records**.
   - Building and maintaining a Bloom filter over an empty table is completely unnecessary.

3. **Application Memory Overhead (Risk of Out-of-Memory):**
   - Loading millions of existing UUID strings from a large dynamic table into Java heap memory creates heavy memory pressure and long garbage collection pauses, risking application crashes.

4. **The Final Adopted Solution (Exact Batched SQL):**
   Instead of an in-memory Bloom filter, the replay service performs **exact batch verification** against ClickHouse:
   - For each batch of 1,000 raw events, the worker extracts their event IDs and runs a single fast query checking which IDs already exist in the target table.
   - **Zero False Positives**: Exact ID matching ensures **no logs are ever mistakenly skipped**.
   - **Constant Bounded Memory**: The application only keeps 1,000 IDs in memory at any given moment, keeping memory footprint minimal.
   - **High Speed**: Because event ID is part of the primary sort key in ClickHouse, this batch check executes in single-digit milliseconds.

---

## 6. Data Integrity Analysis: Can Any Logs Ever Get Missed?

A core design requirement for ULPF is **Zero Log Loss**. Below is an analysis of potential edge cases and how the architecture prevents log loss:

### Case 1: Logs arriving before admin approval
- **What happens**: If credentials are not yet active, unauthorized requests are safely rejected at the API boundary. For sources permitted to send pre-approval telemetry or sample streams, all events are written directly to raw storage.
- **Guarantee**: Because the raw table acts as the permanent source of truth, replay reads and processes every historical record once approval is granted.

### Case 2: Race condition between table creation and live traffic
- **What happens**: A live event might arrive at the exact moment an administrator approves the source.
- **Guarantee**: In the onboarding flow, vendor API credentials are kept inactive until table provisioning and schema mappings are completely finalized. Live traffic cannot enter the system until the analytical table is fully ready to receive data.

### Case 3: Replay process interruption or crash
- **What happens**: A replay job processing a million records experiences a server restart or crash midway through.
- **Guarantee**: Replay uses **keyset cursor pagination** based on a combined timestamp and unique event ID cursor. Processing progresses in deterministic order. If a job is restarted, it can resume from the last recorded cursor position. Furthermore, the exact deduplication check ensures that any previously inserted records are safely skipped, preventing duplicate entries.

### Case 4: Temporary database write failure during live ingestion
- **What happens**: During live streaming, a network spike causes a dynamic analytical table write to fail, while the raw write succeeded.
- **Guarantee**: In ULPF, events are **always saved to the raw table first**. Even if a secondary analytical write fails, the log is safely preserved in raw storage. An administrator can trigger a time-bounded replay to backfill the missing analytical records without data loss.

### Case 5: Unmapped vendor fields
- **What happens**: A vendor's payload contains attributes that were not included in the approved mapping schema.
- **Guarantee**: Mapped attributes are written to dedicated columns, while all unmapped attributes are automatically collected and stored in a compressed unmapped JSON column. No vendor fields are discarded.

### Case 6: Sensor telemetry suppression
- **What happens**: Sensor readings that did not deviate beyond predefined thresholds appear in raw storage but not in analytical tables.
- **Guarantee**: This is an intentional feature of edge sensor telemetry. High-frequency repetitive readings are filtered to optimize storage, while full audit trails remain in the raw archive.

---

## 7. Deduplication & Concurrency Architecture

### Why Application-Level Deduplication?
ClickHouse offers an engine called `ReplacingMergeTree` that removes duplicate rows during background table maintenance. However:
- Background merges run asynchronously at non-deterministic times, meaning queries can still return duplicate rows until a merge occurs.
- Forcing deduplication at query time requires a special table scan option that significantly slows down analytical dashboards and metric queries.

To ensure fast query performance while guaranteeing that no duplicate records are stored, ULPF uses standard `MergeTree` tables combined with **application-level batch deduplication**:
1. Before inserting each batch, the replay worker checks which event IDs already exist in the target table.
2. Pre-existing IDs are skipped and counted in the job metrics.
3. Only verified new events are inserted into the dynamic table.

---

## 8. Failure Recovery, Resumability & Memory Pressure Throttling

### 1. Adaptive Memory Pressure Throttling
A large backfill job streaming millions of historical records could potentially consume excessive database memory, which might interfere with real-time log ingestion.

To protect system stability, the replay service monitors ClickHouse's internal memory metrics before processing each batch:
- It compares current memory utilization against the configured maximum memory threshold.
- If memory pressure exceeds **80%**, the worker automatically introduces a **200-millisecond pause** before continuing.
- This backpressure mechanism ensures that background backfills never degrade real-time ingestion throughput or cluster stability.

### 2. Job Cancellation & Status Monitoring
- Every active replay job maintains an atomic cancellation flag in memory.
- Administrators can cancel an active job at any time using the administrative API.
- The worker checks this flag between batches, halts execution safely without leaving partial batch transactions, and transitions the job state to cancelled.

---

## 9. REST API & Administrative Controls

The replay engine provides administrative endpoints for triggering, monitoring, and managing backfill operations:

### 1. Trigger Replay
- **Endpoint**: `POST /v1/admin/sources/{sourceId}/replay`
- **Access**: Admin role required
- **Parameters (Optional)**:
  - `fromTime`: Start timestamp of the historical window (defaults to 30 days prior).
  - `toTime`: End timestamp of the historical window (defaults to the current time).
  - `chunkSize`: Number of events processed per batch (defaults to 1,000).
- **Behavior**: Validates source status, verifies target schema, estimates total event count, and launches the replay job asynchronously. Returns immediate acceptance with a unique job ID and initial status.

### 2. Monitor Replay Progress
- **Endpoint**: `GET /v1/admin/sources/{sourceId}/replay/status`
- **Access**: Admin role required
- **Returned Information**:
  - Current status (e.g., queued, running, completed, cancelled, or failed).
  - Progress metrics including total estimated events, processed count, inserted count, skipped duplicate count, and failed count.
  - Calculated progress percentage and job start/end timestamps.

### 3. Cancel Active Replay
- **Endpoint**: `DELETE /v1/admin/sources/{sourceId}/replay`
- **Access**: Admin role required
- **Behavior**: Signals the running worker thread to stop cleanly after completing its current batch, preventing further processing while keeping already inserted batches intact.

---

## 10. Summary Matrix & Key Takeaways

| Dimension | Specification |
| :--- | :--- |
| **Primary Purpose** | Transform unmapped historical raw logs from raw storage into queryable dynamic schema tables upon onboarding approval. |
| **Automation** | Automatically invoked on first approval; manually triggerable with custom time ranges via the Admin REST API. |
| **ClickHouse Bloom Filter** | **Active on disk storage**: Token Bloom filter index over raw payloads for ultra-fast text search without full table decompression. |
| **Dedup Bloom Filter** | **Replaced with exact batch checks**: In-memory Bloom filter was rejected to prevent false-positive log drops; exact SQL batch lookups ensure 100% data integrity. |
| **Log Loss Guarantee** | **Zero log loss**: Immutable raw archive acts as permanent source of truth; keyset pagination prevents cursor skips; unmapped fields preserved in dynamic tables. |
| **Backpressure Control** | Automatically throttles (200ms pause) when ClickHouse memory utilization exceeds 80%. |
| **Concurrency Safety** | Time-bounded execution window ensures replay does not collide with incoming real-time logs. |
