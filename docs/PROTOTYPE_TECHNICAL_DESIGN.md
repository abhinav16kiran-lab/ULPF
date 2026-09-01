# Universal Log Framework (ULPF)
## Prototype Technical Design — SIH Prototype

## 1. Scope

The prototype must demonstrate the complete flow from vendor onboarding to runtime ingestion and analytics-ready storage.

```text
Onboarding → Mapping → Human approval → Credential → /v1/events → Raw → Normalize → ClickHouse → Analytics
```

Out of scope: SSO, Kubernetes, public vendor portals, email infrastructure, complex service discovery, and other enterprise deployment infrastructure.

## 2. Vendor Onboarding

```text
Vendor registers
      ↓
sample/schema upload
      ↓
SQLite onboarding request
      ↓
mapping engine
      ↓
proposal + confidence
      ↓
HUMAN REVIEW
      ↓
approve/edit/reject
      ↓
Mapping Version
      ↓
credential
```

A schema update creates a new mapping version; previous mappings remain retained.

## 3. Normal Runtime Processing

```text
POST /v1/events
      ↓
credential authentication
      ↓
vendor/source resolution
      ↓
event_id generation
      ↓
lineage assignment
      ↓
raw persistence
      ↓
active mapping lookup
      ↓
parse
      ↓
normalize
      ↓
ClickHouse canonical table(s)
```

## 4. ClickHouse Prototype Layout

```text
ClickHouse
│
├── ulpf_raw
│   └── raw_events
│
└── ulpf_events
    └── runtime-created canonical tables
```

Initial bootstrap creates both databases and the raw table. Canonical runtime tables are created/extended only after an approved schema decision.

## 5. Raw Event Schema

```sql
CREATE TABLE IF NOT EXISTS ulpf_raw.raw_events
(
    event_id         String,
    vendor_id        String,
    source_id        String,
    lineage_id       String,
    mapping_version  Nullable(UInt32),
    received_at      DateTime64(3) DEFAULT now64(3),
    raw_payload      String
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(received_at)
ORDER BY (vendor_id, received_at, event_id);
```

## 6. Runtime Canonical Schema Rule

Every runtime-created event-derived table must contain:

```text
event_id
lineage_id
```

For one-to-one events:

```text
lineage_id = raw event_id
```

For aggregation:

```text
lineage_id = shared identifier for all contributing raw events
```

A background validator should check the proposal before approval/schema execution.

## 7. Mapping Engine

```text
Incoming field
 ↓
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
Edit-distance fallback for typo/truncation
 ↓
Optional local semantic embedding fallback
 ↓
Proposal
 ↓
Human review
```

### Optional semantic layer

Candidate model:

```text
sentence-transformers/all-MiniLM-L6-v2
```

The model is an embedding model, not required as a generative/chat model.

It should only be invoked for difficult onboarding cases where earlier deterministic layers fail to provide a reliable candidate.

## 8. Domain Vocabulary

The mapping engine may ship a small reusable vocabulary of canonical fields and aliases.

Example:

```text
src_ip:
  source_ip
  sourceaddr
  srcaddress
  client_ip

dst_ip:
  destination_ip
  dest_ip
  destaddr

url:
  uri
  endpoint
  end_point
```

The vocabulary is reused across vendors.

It is tiny relative to event data and does not justify a custom compression mechanism for the prototype.

## 9. Optional Embeddings

If the semantic layer is enabled, embeddings can be persisted locally.

Prototype option:

```text
SQLite
└── mapping embeddings
     ├── field/concept identifier
     ├── model name/version
     └── embedding BLOB
```

MiniLM-L6-v2 produces 384-dimensional embeddings. Float32 storage is approximately 1.5 KB per vector.

Keep the model artifact on disk and load it into RAM only when the semantic fallback is invoked.

## 10. Analytics

Production runtime does not require Python/FastAPI.

```text
React
 ↓
Spring Boot
 ↓
authorization / validation
 ↓
read-only ClickHouse query
 ↓
results
 ↓
React
```

### Predefined mode

Backend-owned query templates can support:

```text
COUNT
SUM
AVG
MIN
MAX
GROUP BY
time-bucketing
```

### Authorized SQL mode

A technical user may enter SQL, but the backend must enforce authorization, read-only semantics, and resource limits.

## 11. Python Development Tooling

Python is optional development/downstream tooling, not a production ULPF dependency.

Possible scripts:

```text
dev-tools/
├── analytics_demo.py
└── ml_demo.py
```

These demonstrate downstream analytics/ML consumption of standardized ClickHouse data.

## 12. ClickHouse Insert Strategy

ClickHouse supports asynchronous inserts.

```text
events
 ↓
ClickHouse async-insert buffer
 ↓
batch
 ↓
persistent data parts
```

Application batching is also possible.

Do not add Vector solely because batching is needed.

## 13. Capacity

There is no fixed “X writes per database” ceiling.

Actual capacity depends on:

- hardware
- event size
- insert batch size
- schema/order keys
- query complexity
- concurrency
- disk throughput
- background merges

Benchmark the real prototype configuration.

## 14. Persistence

```text
Spring Boot container → SQLite persistent volume → ulpf.db

ClickHouse container → ClickHouse persistent volume → event data/state
```

Containers may be recreated without losing persistent state.

## 15. Air-Gapped Deployment

Package all selected container images, model artifacts, configuration, and required dependencies before moving to the isolated environment.

No runtime Internet dependency is allowed.

## 16. Prototype Technology Boundary

```text
Backend          Java + Spring Boot
Control DB       SQLite
Event DB         ClickHouse
Frontend         React
AI mapping       deterministic matcher + optional local embeddings
Analytics        Spring Boot + ClickHouse + React
Python           development/demo only
Vector           optional, only if benchmark justifies it
Containers       Podman-compatible
```

## 17. Critical Rules

- One normal ingestion endpoint.
- Raw event preserved first.
- AI proposes; human approves.
- Mapping versions belong to sources.
- Do not create vendor-specific tables by default.
- Do not let AI directly mutate production schema.
- Runtime canonical schemas are deployment-specific.
- Every event-derived runtime table remains traceable.
- Keep raw data available for replay/reprocessing.
