# Universal Log Framework (ULPF)
## API Specification — SIH Prototype

> Prototype API baseline. Exact payload shapes are still open where the architecture has not fixed them.

## 1. Principles

- Spring Boot is the application/API boundary.
- Normal runtime ingestion uses one endpoint: `POST /v1/events`.
- Onboarding is a separate control-plane workflow.
- Vendors do not connect directly to SQLite or ClickHouse.
- Analytics is read-only and is mediated by Spring Boot.
- `/v1` is the **ULPF API version**, not a vendor mapping version.

## 2. Endpoint Summary

| Endpoint | Purpose | Plane |
|---|---|---|
| `POST /v1/events` | Primary plug-and-play runtime ingestion | Data plane |
| `POST /v1/onboard` | Submit onboarding/source/schema request | Control plane |
| `POST /v1/login` | Prototype authentication | Control plane |
| `GET /v1/notifications` | Load notifications for the logged-in user | Control plane |
| `GET /v1/analytics` | Authorized read-only analytics access to ClickHouse | Analytics |
| `GET /v1/analytics/export/parquet` | Local Parquet batch exporter for AI/ML data lakes | Analytics / Data plane |
| `GET /v1/integrity/verify/{blockId}` | Cryptographic Merkle tree audit verification endpoint | Control / Integrity plane |

## 3. `POST /v1/events`

### Runtime flow

```text
Vendor/source
   ↓
POST /v1/events
   ↓
authenticate credential
   ↓
resolve vendor_id + source_id
   ↓
generate event_id
   ↓
assign lineage_id
   ↓
persist complete raw event
   ↓
resolve active mapping version
   ↓
parse / map / normalize
   ↓
write canonical record(s)
```

### Required rules

1. Authenticate the ingestion credential.
2. Resolve the vendor and source.
3. Generate `event_id` in the application.
4. Assign `lineage_id` before processing.
5. Preserve the complete original event before transformation.
6. Resolve the approved mapping for the source.
7. Normalize and store canonical output.
8. Never delete the raw event because processing fails.
9. Carry the same `event_id` into one-to-one normalized records.
10. A single incoming event may create multiple canonical records; lineage must remain traceable.

### Open

- Exact JSON payload
- Batch request support
- Duplicate/idempotency semantics for retries

## 4. `POST /v1/onboard`

Starts a new vendor/source/schema onboarding request.

Supported request types:

```text
NEW_VENDOR
NEW_SOURCE
SCHEMA_UPDATE
```

Conceptual flow:

```text
submit sample/schema
   ↓
SQLite onboarding request
   ↓
mapping analysis
   ↓
AI proposal
   ↓
HUMAN_REVIEW
   ↓
approve / edit / reject
```

Exact multipart/request format is TBD.

## 5. `POST /v1/login`

Prototype authentication endpoint.

Passwords are never stored in plaintext. SQLite stores a password hash.

Exact token/session mechanism remains an implementation decision.

## 6. `GET /v1/notifications`

Returns notifications belonging to the authenticated user, including onboarding and schema-review outcomes.

Exact response shape is TBD.

## 7. `GET /v1/analytics`

Analytics must follow:

```text
React UI
   ↓
Spring Boot
   ↓
authorization / validation
   ↓
read-only ClickHouse query
   ↓
results
   ↓
React UI
```

The browser never connects directly to ClickHouse.

Minimum protections:

- server-side authentication/authorization
- read-only query enforcement
- allowed-table/column controls where required
- query timeout
- result-size limits

For chart rendering, ClickHouse performs aggregation; React receives compact results.

## 8. Analytics Query Modes

### Predefined analytics

Backend-defined templates may expose:

```text
table
column/metric
aggregation
filters
time range
```

Candidate operations:

```text
COUNT
SUM
AVG
MIN
MAX
GROUP BY
time-bucketing
```

### Authorized SQL

An advanced SQL editor may be provided for authorized users. The flow remains:

```text
SQL editor
 ↓
Spring Boot
 ↓
RBAC / authorization
 ↓
read-only validation
 ↓
limits / timeout
 ↓
ClickHouse
 ↓
results
```

## 9. API Versioning

`/v1/` is independent from `mapping_versions.version`.

Example:

```text
API: /v1/events
Source A mapping: v7
```

## 10. Error Categories

The final API should represent at least:

- authentication failure
- invalid/revoked credential
- unauthorized operation
- malformed event
- unknown source
- inactive source/vendor
- missing mapping
- schema operation failure
- analytics query rejection
- analytics timeout
- validation failure

Exact error-object structure is TBD.

## 11. `GET /v1/integrity/verify/{blockId}`

Cryptographic Merkle Tree audit verification endpoint. Re-computes SHA-256 event hashes for raw logs in ClickHouse corresponding to the specified `blockId`, constructs the binary Merkle root, and compares it against SQLite's persisted root.

### Response format:
```json
{
  "blockId": "blk_9012830192",
  "sourceId": "src_fw_001",
  "eventCount": 100,
  "persistedMerkleRoot": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "calculatedMerkleRoot": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "status": "VERIFIED_INTACT",
  "verifiedAt": "2026-09-08T17:20:00"
}
```

## 12. `GET /v1/analytics/export/parquet`

Automated batch exporter endpoint dumping ClickHouse log chunks into Snappy-compressed binary Apache Parquet files for offline AI/ML model training pipelines and air-gapped data lakes.

### Query Parameters:
- `table` (optional): Target ClickHouse table name (default: `raw_events`)
- `vendorId` (optional): Filter logs by vendor identifier
- `sourceId` (optional): Filter logs by source identifier
- `from` (optional): Start timestamp ISO string
- `to` (optional): End timestamp ISO string
- `limit` (optional): Max row limit (default: `10000`, max: `500000`)

### Response Headers:
- `Content-Type`: `application/vnd.apache.parquet`
- `Content-Disposition`: `attachment; filename="ulpf_export_<vendorId>_<timestamp>.parquet"`

## 13. `GET /v1/analytics/search`

Full-text raw log substring and regex search engine backed by ClickHouse `tokenbf_v1` Bloom filter skip indexing over ZSTD compressed log payloads.

### Query Parameters:
- `q` (required): Substring term or regular expression pattern
- `searchType` (optional): `CONTAINS` (case-insensitive substring search) or `REGEX` (ClickHouse `match()` regex search)
- `vendorId` (optional): Filter logs by vendor identifier
- `sourceId` (optional): Filter logs by source identifier
- `limit` (optional): Max row limit (default: `200`, max: `5000`)

### Response Format:
```json
{
  "query": "error",
  "totalMatches": 42,
  "executionTimeMs": 14,
  "events": [
    {
      "eventId": "evt_8941a20",
      "lineageId": "lin_001",
      "vendorId": "cisco",
      "sourceId": "fw_east",
      "mappingVersion": 1,
      "receivedAt": "2026-09-09T10:15:00",
      "rawPayload": "{\"level\":\"error\",\"msg\":\"Connection refused\"}"
    }
  ]
}
```

## 14. `GET /v1/analytics/timeseries`

Time-series histogram aggregation endpoint calculating total log throughput and error spike volume per interval for Grafana-style dashboard visual rendering.

### Query Parameters:
- `q` (optional): Substring term to filter histogram counts
- `interval` (optional): Time bucket interval (e.g. `5m`, `1h`)

### Response Format:
```json
[
  {
    "timestamp": "2026-09-09T10:00:00Z",
    "totalCount": 450,
    "errorCount": 12
  },
  {
    "timestamp": "2026-09-09T10:05:00Z",
    "totalCount": 510,
    "errorCount": 48
  }
]
```


