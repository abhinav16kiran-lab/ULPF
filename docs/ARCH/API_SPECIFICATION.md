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
