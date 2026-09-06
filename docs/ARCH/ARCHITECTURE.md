# Universal Log Framework (ULPF)
## Architecture — SIH Prototype

## 1. Core Objective

ULPF accepts heterogeneous logs/events, preserves the original data, maps source-specific fields into a canonical representation, and stores analytics-ready data.

The prototype is intentionally modular without adding infrastructure without a demonstrated need.

## 2. High-Level View

```text
                 ULPF
                  │
        ┌─────────┴─────────┐
        ↓                   ↓
  CONTROL PLANE         DATA PLANE
        │                   │
   Web UI/API          POST /v1/events
        │                   │
     SQLite             RAW FIRST
                            │
                     parse/map/normalize
                            │
                        ClickHouse
                            │
                         Analytics
```

## 3. Control Plane

```text
User
 ↓
Vendor
 ↓
Source
 ├── Credentials
 └── Mapping Versions
```

SQLite stores:

```text
users
vendors
sources
credentials
mapping_versions
onboarding_requests
notifications
```

Onboarding and configuration remain separate from high-volume ingestion.

## 4. Data Plane

```text
POST /v1/events
      ↓
authenticate
      ↓
resolve vendor/source
      ↓
generate event_id
      ↓
assign lineage_id
      ↓
persist raw event
      ↓
active mapping
      ↓
parse / normalize
      ↓
canonical record(s)
      ↓
ClickHouse
```

Raw preservation happens before transformation.

## 5. ClickHouse

Prototype deployment:

```text
ONE ClickHouse INSTANCE
ONE ClickHouse CONTAINER
        │
        ├── ulpf_raw
        │     └── raw_events
        │
        └── ulpf_events
              └── runtime canonical tables
```

Two logical databases do not create resource isolation.

## 6. Canonical Schema

Canonical classes are stable concepts, not vendor-specific tables by default.

Examples:

```text
Network Activity
Web / HTTP Activity
Authentication Activity
Database Activity
DNS Activity
File / Process Activity
Sensor / Telemetry
```

Exact taxonomy/version for the demo remains an open decision.

## 7. Runtime Schema Management

```text
Vendor sample
   ↓
Mapping/schema proposal
   ↓
Human review
   ↓
Approve / Edit / Reject
   ↓
Schema Manager
   ↓
controlled ClickHouse DDL
```

AI never receives unrestricted production schema mutation rights.

Customer-specific runtime schema changes are not maintained as ULPF source-code migrations.

## 8. Event Identity and Lineage

Every raw event receives `event_id` in Spring Boot.

`lineage_id` identifies the raw event or group of raw events represented by a normalized record.

```text
ordinary:
raw E123 → normalized E123
lineage = E123

aggregated:
E001 ─┐
E002 ─┼→ L900 → normalized N500
E003 ─┘
```

Every event-derived runtime table must contain `event_id` and `lineage_id`.

## 9. Sensor Optimization

Raw telemetry is always preserved.

The normalized analytical representation may compress repeated values using configured thresholds:

```text
within delta → continue/extend
beyond delta or max interval → emit
```

## 10. Mapping Engine

Core deterministic path:

```text
Normalize
 ↓
Tokenize
 ↓
Dictionary / aliases
 ↓
TF-IDF
 ↓
Candidate ranking
 ↓
Confidence
 ↓
Threshold
 ↓
Edit distance where useful
 ↓
Unknown / mapping proposal
 ↓
Human review
```

Optional semantic fallback:

```text
all-MiniLM-L6-v2 embeddings
```

This is only for difficult semantic cases and is a stretch goal for the prototype.

## 11. Analytics

Production path:

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

ClickHouse aggregates. React renders. Python is not required for production analytics.

Optional Python development tools may consume ClickHouse data for data-science/ML experiments.

## 12. Air-Gapped Operation

All production runtime dependencies must be available locally before deployment.

If semantic embeddings are used:

```text
model file on disk
      ↓
loaded only when difficult onboarding requires it
      ↓
local inference
```

No cloud AI dependency is required.

## 13. Containerization

Target production/demo packaging:

```text
Podman
 ├── Spring Boot
 ├── React
 ├── ClickHouse
 ├── optional local AI runtime
 └── optional Vector
```

Persistent storage is required for SQLite and ClickHouse.

## 14. Design Rules

- One normal runtime ingestion endpoint.
- Raw first.
- AI proposes; human approves.
- Mappings belong to sources.
- Canonical classes are stable.
- Unknown fields are never silently dropped.
- Event-derived runtime tables must remain traceable.
- Browser never connects directly to ClickHouse.
- No infrastructure is added without a concrete reason.
