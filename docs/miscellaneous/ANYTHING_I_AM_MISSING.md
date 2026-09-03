# ULPF — Anything I Am Missing?
## Final Gap Review

This document captures unresolved decisions and easy-to-miss implementation details discovered while consolidating the prototype design.

## 1. Must Resolve

### 1.1 Canonical taxonomy
Finalize the exact event classes/taxonomy and version used by the demo.

### 1.2 Runtime schema policy
Define the exact rules for:

```text
existing table + new field → ALTER
new canonical event class → CREATE
same concept already exists → reuse
```

Prefer extension/reuse before creating a new event class.

### 1.3 Schema Manager
Implement the controlled application mechanism that:

- discovers current ClickHouse schema
- validates approved proposals
- generates safe DDL
- applies approved changes
- records result/failure

AI must never have unrestricted DDL authority.

### 1.4 Schema Registry location
The architecture names a schema registry, but its exact persistence mechanism is still open.

### 1.5 AI event-classification scope
The existing draft currently says AI may identify event classes, but the project discussion has treated detailed AI scope as unsettled. Explicitly decide this instead of allowing wording to drift.

### 1.6 Ingestion duplicate semantics
Decide whether retrying the same incoming event is:

```text
accepted as duplicate
```

or:

```text
deduplicated/idempotent
```

`MergeTree` does not provide ordinary relational uniqueness semantics by itself.

## 2. Analytics Decisions

Define the exact demonstrations:

```text
Events over time
Events by category/vendor/source
Paginated event inspection
```

Optional:

```text
heatmap
pie
advanced SQL
```

Define who can see:

- raw payloads
- vendor data
- source data
- particular tables
- arbitrary SQL

The frontend must not enforce authorization by itself.

## 3. Mapping Engine

### Confidence
Define what the numerical confidence means. Unless calibrated, describe it as a similarity/confidence score rather than probability.

### Thresholds
Choose thresholds from a labelled test set rather than intuition.

### Candidate separation
Consider using the gap between the best and second-best candidate as an ambiguity signal.

### Value/type evidence
A future improvement can use sample values and expected canonical type:

```text
field name + value shape + canonical type
```

This is not required for the first vertical slice.

## 4. Domain Vocabulary

Keep a small, version-controlled vocabulary of canonical fields and aliases.

Rules:

- reviewable
- shipped with the application
- reused across vendors
- no uncontrolled self-modifying behavior for the prototype
- compression unnecessary at prototype scale

Human-approved corrections can inform later vocabulary releases.

## 5. Embedding Layer

If Layer 4 is enabled:

- [ ] pin model/runtime versions
- [ ] package model locally
- [ ] keep model on disk
- [ ] load only when required
- [ ] persist embeddings locally if useful
- [ ] store model name/version with embeddings
- [ ] rebuild embeddings if the model version changes
- [ ] verify the entire path works offline

Candidate:

```text
sentence-transformers/all-MiniLM-L6-v2
```

## 6. ClickHouse Details

Still to finalize after workload testing:

- partition key
- sorting/order key
- retention
- compression/settings
- expected maximum raw-event size
- query limits
- ingestion batch strategy

Do not confuse:

```text
one ClickHouse instance
```

with:

```text
one write at a time
```

ClickHouse supports concurrent reads/writes; practical capacity depends on resources and workload.

## 7. Buffering

Vector should remain optional until measurement proves its value.

Possible alternatives:

```text
ClickHouse async inserts
application-level batching
Vector/dedicated buffer
```

Choose based on measured ingestion behavior.

## 8. Persistence vs Backup

Container persistence:

```text
container → persistent volume
```

is not a disaster-recovery backup strategy.

A future backup/snapshot plan may be required for production deployments but is not necessary for the SIH prototype unless explicitly required.

## 9. Vendor / Source Lifecycle

Define source-state semantics explicitly for:

```text
ACTIVE
SUSPENDED
REVOKED
```

Define precedence when a vendor is suspended but a source is active, or vice versa.

## 10. Mapping Lifecycle

Verify:

- only one active mapping per source
- previous mappings remain retained
- processing records the mapping version used
- retired mappings can be identified for replay/reprocessing

The exact retention duration is open.

## 11. Replay / Reprocessing

At minimum, demonstrate that preserved raw events can be located using `event_id`/`lineage_id`.

A complete replay engine can be deferred.

## 12. Distributed Deployment

The architecture discusses coexistence of old/new mappings or schemas during rollout.

The SIH prototype does not need a full distributed cluster, but the model must not depend on overwriting old mappings immediately.

## 13. Licensing

Before submission/deployment review the exact licenses and redistribution conditions for:

- Spring/Java dependencies
- ClickHouse distribution/version
- React dependencies
- Vector, if used
- local AI runtime
- selected embedding model
- Python development libraries

Record the actual versions used.

## 14. Submission Story

The cleanest demonstration is:

```text
Different vendors
      ↓
different formats
      ↓
ULPF onboarding + mapping
      ↓
human approval
      ↓
common canonical representation
      ↓
raw preservation + lineage
      ↓
ClickHouse
      ↓
analytics / visualization
```

The demo should spend time on ULPF's differentiator, not on infrastructure that has no visible purpose.

## 15. Things Not To Add Without A Concrete Need

```text
Kafka
Kubernetes
Redis
API gateway
service mesh
second ClickHouse instance
vector database
full MLOps stack
Python production analytics service
```

## 16. Final Definition of Done

Fresh deployment can demonstrate:

```text
Vendor registers
   ↓
sample uploaded
   ↓
mapping proposal
   ↓
human review
   ↓
approved mapping
   ↓
credential
   ↓
/v1/events
   ↓
raw preserved
   ↓
normalized event
   ↓
lineage back to raw
   ↓
second vendor → same canonical model
   ↓
analytics query
   ↓
visualization
```

If this path is reliable, the prototype has demonstrated the core ULPF value proposition.
