# ULPF — Everything That Needs To Be Done Before Prototype Submission

## P0 — Core System

### Repository
- [x] Git repository finalized
- [x] README/setup instructions
- [x] `.env.example`, `.gitignore`, `.gitattributes`
- [x] Architecture/API/database documentation
- [x] Podman/Compose configuration

### Control Plane
- [x] SQLite schema
- [x] Users/vendor/source/credential CRUD
- [x] Mapping-version CRUD
- [x] Onboarding requests
- [x] Notifications
- [x] Login + basic RBAC
- [x] API-key generation and hashed storage
- [x] Vendor/source status handling

### Onboarding
- [x] `/onboard` UI
- [x] sample-log upload
- [x] optional schema/documentation upload
- [x] onboarding request creation
- [x] mapping proposal generation
- [x] confidence output
- [x] unknown-field proposal
- [x] admin `/admin` review screen
- [x] current canonical schema display
- [x] proposal-vs-schema comparison
- [x] edit/approve/reject
- [x] mapping version activation
- [x] credential issuance/activation

## P0 — Runtime Ingestion
- [x] `POST /v1/events`
- [x] credential authentication
- [x] vendor/source resolution
- [x] `event_id` generation
- [x] `lineage_id` assignment
- [x] raw-first persistence
- [x] active mapping lookup
- [x] parse/normalize
- [x] normalized ClickHouse write
- [x] error handling without raw-data loss
- [x] raw ↔ normalized traceability

## P0 — ClickHouse
- [x] One ClickHouse instance/container
- [x] `ulpf_raw` database
- [x] `ulpf_events` database
- [x] `raw_events` table (with 7-day ZSTD(15) TTL compression)
- [x] persistent ClickHouse volume
- [x] runtime schema manager
- [x] event-table `event_id` + `lineage_id` validation
- [x] runtime table creation test
- [x] runtime column addition test
- [x] raw-to-normalized backtracking test

## P0 — Analytics
- [x] React analytics page
- [x] Spring Boot analytics API
- [x] server-side authorization
- [x] read-only ClickHouse queries
- [x] predefined query templates
- [x] compact result JSON
- [] table view
- [] at least one chart
- [x] query timeout/result limit
- [x] direct browser→ClickHouse blocked

Recommended initial visuals:

```text
Events over time
Events by category/vendor/severity
Paginated event table
```

## P1 — Mapping Engine

Implement:

```text
1. Dictionary/aliases
2. Normalization/tokenization
3. TF-IDF
4. Candidate ranking/confidence
5. Edit distance
6. Unknown-field handling
```

Optional stretch:

```text
7. all-MiniLM-L6-v2 embedding fallback
```

## P1 — Mapping Quality

Create a labelled field-name dataset containing:

- normal aliases
- abbreviations
- typos
- truncations
- semantic alternatives
- genuinely unknown fields

Measure:

```text
correct mappings
false positives
false negatives
unknown detection
confidence behavior
```

Do not call a confidence score a probability unless calibrated.

## P1 — Sensor Optimization

- [ ] delta-based emission
- [ ] max-interval emission
- [ ] preserve every raw sensor reading
- [ ] lineage grouping for an emitted aggregate
- [ ] verify raw-to-aggregate backtracking

Current rule:

```text
IF |current - last_emitted| >= delta
       → emit
ELSE IF time_since_last_emission >= max_interval
       → emit
ELSE
       → don't emit analytics event
```

## P1 — ClickHouse Performance

Benchmark:

- [ ] raw ingestion
- [ ] raw + normalized ingestion
- [ ] application batching
- [ ] asynchronous inserts
- [ ] ingestion + SELECT concurrency
- [ ] analytical query latency
- [ ] CPU/RAM/disk
- [ ] merge behavior

Record:

```text
events/sec
MB/sec
insert latency
query latency
resource use
```

Do not invent a universal ClickHouse ceiling. Measure the real deployment.

Do not create a second ClickHouse instance unless the benchmark demonstrates a need.

## P1 — Containerization

Target demo/production package:

```text
Spring Boot
React
ClickHouse
optional AI runtime
optional Vector
```

Persistent volumes:

```text
SQLite → volume → ulpf.db
ClickHouse → volume → ClickHouse state/data
```

Development may still run application components natively.

## P2 — Vector

Only if needed after measurement.

If retained:

- [ ] define exact role
- [ ] pin version
- [ ] store `vector.yaml`
- [ ] containerize
- [ ] benchmark benefit

## P2 — Python

Production does not require Python/FastAPI for ordinary analytics.

Optional development scripts:

```text
dev-tools/
├── analytics_demo.py
└── ml_demo.py
```

These demonstrate downstream use of standardized ClickHouse data.

## P0 — End-to-End Demo

- [ ] Vendor registration
- [ ] Sample upload
- [ ] Mapping proposal
- [ ] Human edit of ambiguous mapping
- [ ] Approval
- [ ] API key
- [ ] `/v1/events`
- [ ] Raw preservation
- [ ] Normalized storage
- [ ] Second vendor with different naming but same semantics
- [ ] Runtime schema extension
- [ ] Mapping-version retention
- [ ] Analytics query
- [ ] Visualization
- [ ] Raw-to-normalized lineage demonstration
- [ ] Sensor optimization demonstration if implemented

## P0 — Submission

- [ ] Source code link
- [ ] README
- [ ] Architecture document ≤ 2 pages
- [ ] Demo video ≤ 2 minutes
- [ ] Technical presentation ≤ 5 slides
- [ ] Reproducible setup
- [ ] Air-gapped deployment story
- [ ] Dependency/license review
