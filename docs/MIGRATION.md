# Universal Log Processing Framework (ULPF)
## Enterprise Log Migration Guide

This guide outlines the 4 production-tested strategies for migrating enterprise log pipelines, data lakes, and legacy SIEM systems (Elasticsearch, OpenSearch, Splunk, PostgreSQL, AWS S3, Apache Kafka) to **ULPF**.

---

## 🏗 Migration Overview

ULPF is designed to coexist with or replace legacy enterprise log databases without requiring application refactoring or downtime.

```text
                     Enterprise Infrastructure
                                │
       ┌────────────────────────┼────────────────────────┐
       ↓                        ↓                        ↓
Strategy 1: Dual Sink   Strategy 2: Bulk Import   Strategy 4: Event Bus
(Fluentbit / Vector)     (Files / S3 Archives)    (Kafka Engine)
       │                        │                        │
       └────────────────────────┼────────────────────────┘
                                ↓
                     ULPF Core Data Plane
                                │
                                ↓
                 Strategy 3: AI Field Mapping
                                │
                                ↓
                     ClickHouse Columnar Storage
```

---

## 1. Strategy 1: Dual-Write Proxy Bridge (Zero Downtime)

### Overview
Add ULPF as a secondary output destination in your existing enterprise log shipper (**Fluentbit, Logstash, Vector, Rsyslog, Telegraf, Splunk Heavy Forwarder**).

### Configuration Examples

#### Fluentbit (`fluent-bit.conf`)
```ini
[OUTPUT]
    Name        http
    Match       *
    Host        ulpf-core.internal
    Port        8080
    URI         /v1/events
    Header      X-API-Key ulpf_live_YOUR_API_KEY
    Format      json
```

#### Vector (`vector.toml`)
```toml
[sinks.ulpf_output]
type = "http"
inputs = ["raw_logs"]
uri = "http://ulpf-core.internal:8080/v1/events"
encoding.codec = "json"
headers.X-API-Key = "ulpf_live_YOUR_API_KEY"
```

### Key Benefits
- **Zero Ingestion Downtime**: Production applications continue sending logs uninterrupted.
- **Side-by-Side Validation**: Validate ULPF telemetry & query speeds while legacy systems remain online.

---

## 2. Strategy 2: Bulk Historical Log Import

### Overview
Migrate terabytes of historical log archives (`.json`, `.json.gz`, `.log`, `.csv`) from legacy databases or cloud object stores (**AWS S3, Azure Blob, GCS**).

### Method A: REST File Upload API (`POST /v1/analytics/import/file`)
Upload historic log batches directly via ULPF Web App (`/analytics`) or `curl`:

```bash
curl -X POST http://localhost:8080/v1/analytics/import/file \
  -H "Authorization: Bearer YOUR_ADMIN_JWT" \
  -F "file=@legacy_firewall_20260901.json.gz" \
  -F "vendorId=cisco" \
  -F "sourceId=fw_east"
```

*Response*:
```json
{
  "status": "SUCCESS",
  "fileName": "legacy_firewall_20260901.json.gz",
  "importedCount": 12500,
  "executionTimeMs": 142,
  "vendorId": "cisco",
  "sourceId": "fw_east"
}
```

### Method B: ClickHouse Native S3 / File Import
For multi-terabyte data lakes, execute ClickHouse native parallel S3 queries directly:

```sql
INSERT INTO ulpf_raw.raw_events (event_id, vendor_id, source_id, received_at, raw_payload)
SELECT generateUUIDv4(), 'legacy_vendor', 's3_archive', now(), line
FROM s3('https://my-log-bucket.s3.amazonaws.com/archives/2026/*/*.json.gz', 'JSONAsString');
```

---

## 3. Strategy 3: AI-Powered Field Mapping (Zero Manual Schema Rewriting)

### Overview
Eliminate manual database schema migrations when legacy log attributes use non-standard field names (e.g. `client_ip` vs `src_addr` vs `ip_src`).

### How ULPF Handles Mapping Automatically
1. Export a single sample JSON payload from your legacy database.
2. Submit the payload via ULPF Onboarding (`/v1/onboard` or `/onboard` Web UI).
3. ULPF’s **4-Layer AI Mapping Engine** automatically normalizes attributes:
   - **Layer 1**: Dictionary Alias Matcher (`mapping_aliases`)
   - **Layer 2**: TF-IDF & Character N-Gram Matcher
   - **Layer 3**: Levenshtein Typo Distance Matcher
   - **Layer 4**: Local `all-MiniLM-L6-v2` Vector Embeddings
4. Admin approves the proposal in the **Admin Dashboard** (`/admin`).

### Key Benefits
- Zero application code rewrites.
- Ingestion (`POST /v1/events`) operates with **0% AI runtime overhead** after onboarding.

---

## 4. Strategy 4: Direct Event Bus Connection (Apache Kafka / RabbitMQ)

### Overview
Ingest high-throughput log streams directly from enterprise message queues with zero application code changes.

### ClickHouse Engine Integration

Create a ClickHouse Kafka Engine table linked to your enterprise Kafka topic:

```sql
CREATE TABLE ulpf_raw.kafka_ingest_stream (
    raw_payload String
) ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka-broker.internal:9092',
         kafka_topic_list = 'enterprise-app-logs',
         kafka_group_name = 'ulpf_consumer_group',
         kafka_format = 'JSONAsString';

CREATE MATERIALIZED VIEW ulpf_raw.mv_kafka_to_raw TO ulpf_raw.raw_events AS
SELECT 
    generateUUIDv4() AS event_id,
    generateUUIDv4() AS lineage_id,
    'kafka_vendor' AS vendor_id,
    'kafka_source' AS source_id,
    1 AS mapping_version,
    now64(3) AS received_at,
    raw_payload
FROM ulpf_raw.kafka_ingest_stream;
```

---

## ⏱ Expected Migration Timeline

| Step | Operation | Timeline |
| :--- | :--- | :--- |
| **Phase 1** | Submit sample log payload to AI Onboard (`/onboard`) | **< 5 Minutes** |
| **Phase 2** | Configure dual HTTP sink on Fluentbit/Vector or Kafka view | **< 15 Minutes** |
| **Phase 3** | Import historical archives via `POST /v1/analytics/import/file` | **Minutes to Hours** |
| **Phase 4** | Validate analytics queries & decommission legacy database | **1 Click** |
