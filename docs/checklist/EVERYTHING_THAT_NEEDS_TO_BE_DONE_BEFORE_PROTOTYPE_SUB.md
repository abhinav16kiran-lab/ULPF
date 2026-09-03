# ULPF — Everything That Needs To Be Done Before Prototype Submission

## P0 — Core System

### Repository
- [ ] Git repository finalized
- [ ] README/setup instructions
- [ ] `.env.example`, `.gitignore`, `.gitattributes`
- [ ] Architecture/API/database documentation
- [ ] Podman/Compose configuration

### Control Plane
- [ ] SQLite schema
- [ ] Users/vendor/source/credential CRUD
- [ ] Mapping-version CRUD
- [ ] Onboarding requests
- [ ] Notifications
- [ ] Login + basic RBAC
- [ ] API-key generation and hashed storage
- [ ] Vendor/source status handling

### Onboarding
- [ ] `/onboard` UI
- [ ] sample-log upload
- [ ] optional schema/documentation upload
- [ ] onboarding request creation
- [ ] mapping proposal generation
- [ ] confidence output
- [ ] unknown-field proposal
- [ ] admin `/admin` review screen
- [ ] current canonical schema display
- [ ] proposal-vs-schema comparison
- [ ] edit/approve/reject
- [ ] mapping version activation
- [ ] credential issuance/activation

## P0 — Runtime Ingestion
- [ ] `POST /v1/events`
- [ ] credential authentication
- [ ] vendor/source resolution
- [ ] `event_id` generation
- [ ] `lineage_id` assignment
- [ ] raw-first persistence
- [ ] active mapping lookup
- [ ] parse/normalize
- [ ] normalized ClickHouse write
- [ ] error handling without raw-data loss
- [ ] raw ↔ normalized traceability

## P0 — ClickHouse
- [ ] One ClickHouse instance/container
- [ ] `ulpf_raw` database
- [ ] `ulpf_events` database
- [ ] `raw_events` table
- [ ] persistent ClickHouse volume
- [ ] runtime schema manager
- [ ] event-table `event_id` + `lineage_id` validation
- [ ] runtime table creation test
- [ ] runtime column addition test
- [ ] raw-to-normalized backtracking test

## P0 — Analytics
- [ ] React analytics page
- [ ] Spring Boot analytics API
- [ ] server-side authorization
- [ ] read-only ClickHouse queries
- [ ] predefined query templates
- [ ] compact result JSON
- [ ] table view
- [ ] at least one chart
- [ ] query timeout/result limit
- [ ] direct browser→ClickHouse blocked

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
