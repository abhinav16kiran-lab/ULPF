# Multi-Model Design Comparison — PS 26156 (Universal Log Pre-processing Framework)

**Purpose:** Run the same forcing prompt through multiple AI models, independently of our actual codebase, to see what different perspectives surface — blind spots, alternative architectures, things we hadn't considered.

**Method:** Identical prompt (below) given to each model, with zero prior context about our actual ULPF implementation, so each answer is a genuinely independent take on the raw problem statement.

---

## The Prompt Used

*(See `docs/MULTI_MODEL_PROMPT.md` or the message this was copied from — full forcing prompt with the complete official PS 26156 text embedded.)*

---

## Model 1: Claude (Sonnet)

**Overall architecture:**
A single Spring Boot monolith with two logical planes inside the same deployable JAR: a control plane (onboarding, auth, mapping approval) backed by an embedded SQLite file, and a data plane (ingestion, normalization, storage) backed by a columnar analytical store. One ingestion endpoint accepts logs regardless of source; a pipeline inside the same process handles raw preservation, parsing, mapping resolution, and normalization before writing to two logical destinations (raw table + normalized table) in the same database engine. A lightweight local ML model (sentence-embedding based) assists a human reviewer in proposing field mappings for new sources, but never writes directly to production schema — every mapping proposal requires human approval before activation.

**Tech stack + justification:**
- **Backend:** Java + Spring Boot — mature ecosystem, strong typing reduces parsing bugs at scale, first-class JDBC support for both SQLite and ClickHouse-style engines, easy to containerize as a single JAR.
- **Control-plane storage:** SQLite — genuinely sufficient for control-plane data volume (users, vendors, mappings — thousands, not billions of rows), zero operational overhead (no separate DB server to deploy in an air-gapped environment), file-based so it trivially satisfies the air-gap requirement.
- **Data-plane storage:** ClickHouse — purpose-built for billions-of-rows analytical workloads, columnar storage makes aggregation queries (the actual analytics use case) fast, supports async batched inserts to handle high ingestion throughput without a message queue as a hard requirement.
- **AI mapping layer:** a local, offline sentence-embedding model (e.g., a small ONNX-exported MiniLM-class model) — deliberately not a hosted LLM API, because the air-gap requirement (point j) makes any network-dependent AI service a non-starter. A multi-layer matching approach (exact alias dictionary → statistical similarity → typo/fuzzy tolerance → semantic embedding fallback) keeps the expensive embedding step as a last resort, not the default path, which matters for the "billions of events" throughput requirement.
- **Containerization:** Podman/Docker-compatible images — satisfies point (k) directly; two containers (app + ClickHouse), not a sprawling microservice mesh, to keep the air-gapped deployment package small and self-contained.

**How each expected-solution point is satisfied:**
- **(a) Preserve raw data:** every incoming event is written to a raw, append-only table *before* any parsing/mapping is attempted — a mapping failure never touches or deletes the original.
- **(b) Extract source-specific attributes:** a format-detection/parsing layer inspects the payload (JSON/CSV/Syslog/etc.) and extracts a flat set of candidate fields before mapping begins.
- **(c) Normalize into common taxonomy:** approved per-source mappings translate vendor-specific field names into a fixed canonical schema (e.g., `source_ip`, `event_type`) shared across all vendors.
- **(d) Traceability:** every normalized record carries the same internal `event_id` as its raw counterpart, plus the mapping *version* used, so any normalized row can be traced back to exactly what raw data and what mapping logic produced it.
- **(e) Plug-and-play onboarding:** a dedicated onboarding workflow (upload sample → AI proposes mapping → human approves → credential issued) is fully separate from the runtime ingestion path — once onboarded, a new vendor needs nothing but an API key and the one ingestion endpoint.
- **(f) Unified visibility:** all vendors' normalized data lands in the same canonical event-class tables (network events, auth events, etc.), so a single query surface covers all sources without vendor-specific dashboards.
- **(g) SIEM/Data Lake integration:** the columnar store is queryable via standard SQL, and raw events remain independently queryable/exportable, so either the normalized or raw layer can feed downstream SIEM/data-lake tooling without a proprietary export format.
- **(h) AI/ML-ready:** normalized, canonical-schema data with consistent field semantics is precisely the input shape ML/anomaly-detection pipelines need — no per-vendor preprocessing required downstream.
- **(i) Reduced parser effort:** the AI mapping layer proposes field-level mappings automatically for new sources instead of requiring a human to write a bespoke parser per vendor from scratch — human review remains mandatory, but the first draft is automated.
- **(j) Air-gapped deployable:** no component in this design requires external network access at runtime — local embedded DB, local ONNX model, no calls to hosted AI APIs.
- **(k) Container-packaged:** the entire application ships as container images with persistent volumes for both databases.

**Scale (billions of events/day):** columnar storage with time-based partitioning, async/batched inserts instead of one write per event, and keeping the expensive AI matching path out of the hot ingestion loop (it only runs once per new source during onboarding, not per event at runtime) are the three biggest levers.

**Air-gapped handling:** every dependency (ML model weights, container images, database engines) must be pre-downloaded and bundled into the deployment package rather than pulled at runtime — nothing in the request path calls out to the internet.

**What I'd cut from a hackathon prototype vs. keep essential:** essential — raw preservation, one ingestion endpoint, mandatory human review, basic normalization for 3-4 event types. Cuttable for a prototype — full OCSF schema compliance, production-grade key rotation, distributed multi-node schema rollout, message-queue buffering (only needed once measured throughput actually requires it).

---

## Model 2: # Universal Log Pre-processing Framework (ULPF)

## Smart India Hackathon 2026 — Problem Statement 26156

**Organization:** National Technical Research Organisation (NTRO)
**Category:** Software
**Theme:** Blockchain & Cybersecurity
**Problem Statement:** Universal Log Pre-processing Framework (ULPF)

---

# 1. Executive Summary

The Universal Log Pre-processing Framework (ULPF) is designed as a **single, unified, scalable software application** capable of ingesting logs from heterogeneous hardware and software sources, preserving the original events without information loss, parsing source-specific formats, normalizing them into a common cybersecurity event taxonomy, maintaining complete traceability to the original event, and producing analytics-ready output for SIEM, data lake, threat hunting, anomaly detection, and machine learning systems.

ULPF is designed as a **modular monolith**, not a collection of independently deployed microservices.

The application consists of logically separated modules for:

* Event ingestion
* Format detection
* Source identification
* Source-specific parsing
* Field normalization
* Schema validation
* Traceability
* Parser/source onboarding
* Output routing
* Analytics
* AI/ML inference
* Monitoring
* Security and access control

All of these modules belong to **one ULPF codebase and one deployable application**.

External infrastructure components such as Kafka, PostgreSQL, ClickHouse, and MinIO are used as specialized infrastructure dependencies. They are not separate ULPF applications or microservices.

The architecture is designed to support:

* Heterogeneous log formats
* Vendor-independent normalization
* Lossless raw-event preservation
* Billions of events per day
* SIEM integration
* Data lake integration
* AI/ML analytics
* Plug-and-play source onboarding
* Air-gapped deployment
* Containerized deployment
* Cybersecurity and forensic traceability

---

# 2. Problem Requirements

The system must address the following requirements from SIH Problem Statement 26156:

| Requirement | Description                                                  |
| ----------- | ------------------------------------------------------------ |
| a           | Preserve complete raw event data without information loss    |
| b           | Extract and parse source-specific attributes                 |
| c           | Normalize fields into a common event taxonomy                |
| d           | Maintain traceability between normalized and original events |
| e           | Plug-and-play onboarding of new log sources                  |
| f           | Unified visibility across enterprise environments            |
| g           | Efficient SIEM and Data Lake integration                     |
| h           | AI/ML-ready security and operational analytics               |
| i           | Reduced parser development effort                            |
| j           | Deployment in an air-gapped network                          |
| k           | Containerized/platform-independent deployment                |

The current scope specifically requires processing of **perimeter network device-generated logs and events regardless of vendor, source, format, or technology**.

---

# 3. Overall Architecture

## 3.1 Architectural Style

ULPF uses a:

> **Horizontally scalable modular-monolith architecture with an event-streaming backbone.**

The application is one coherent software system.

```text
                    ANY LOG SOURCE
                         |
        +----------------+----------------+
        |                |                |
      Syslog            HTTP           File/API
        |                |                |
        +----------------+----------------+
                         |
                         v
              +----------------------+
              |    ULPF APPLICATION   |
              |     Java 21 +        |
              |     Spring Boot      |
              +----------+-----------+
                         |
                         v
              +----------------------+
              |   Ingestion Layer    |
              +----------+-----------+
                         |
                         v
              +----------------------+
              | Raw Event Preservation|
              +----------+-----------+
                         |
                         +--------------------+
                         |                    |
                         v                    v
                 MinIO / S3             Kafka Stream
                 Raw Storage                 |
                                              v
                                    +------------------+
                                    | Format Detection |
                                    +--------+---------+
                                             |
                                             v
                                    +------------------+
                                    | Source Detection |
                                    +--------+---------+
                                             |
                                             v
                                    +------------------+
                                    | Parser Engine    |
                                    +--------+---------+
                                             |
                                             v
                                    +------------------+
                                    | Normalization    |
                                    | Engine           |
                                    +--------+---------+
                                             |
                                             v
                                    +------------------+
                                    | Validation &     |
                                    | Enrichment        |
                                    +--------+---------+
                                             |
                                             v
                                    +------------------+
                                    | Traceability     |
                                    +--------+---------+
                                             |
                                             v
                                    +------------------+
                                    | Output Router    |
                                    +--------+---------+
                                             |
                         +-------------------+-------------------+
                         |                   |                   |
                         v                   v                   v
                       Kafka             ClickHouse         Data Lake
                     SIEM output         Analytics DB       Parquet/S3
                                             |
                                             v
                                    +------------------+
                                    | Analytics / ML   |
                                    | / Dashboard      |
                                    +------------------+
```

---

# 4. Complete Data Flow

The complete ULPF pipeline is:

```text
Raw Event
    |
    v
Ingestion
    |
    v
Raw Preservation
    |
    v
Format Detection
    |
    v
Source Identification
    |
    v
Source-Specific Parsing
    |
    v
Intermediate Representation
    |
    v
Common Schema Normalization
    |
    v
Validation
    |
    v
Optional Enrichment
    |
    v
Traceability Metadata
    |
    v
Analytics-Ready Event
    |
    +------------------+
    |                  |
    v                  v
   SIEM             Data Lake
    |
    v
Analytics / Threat Hunting / ML
```

---

# 5. Raw Event Ingestion

ULPF accepts events from multiple sources.

## Supported ingestion mechanisms

### Syslog

* UDP
* TCP
* TLS-secured Syslog where required

### HTTP

* REST API
* JSON payloads
* CEF/LEEF payloads

### File ingestion

* CSV
* JSON
* XML
* text logs
* exported firewall logs

### Future ingestion options

The architecture can be extended to support:

* Kafka input
* SFTP
* Windows Event Logs
* cloud log APIs
* message queues
* custom network protocols

---

# 6. Raw Event Preservation

Raw-event preservation is performed **before parsing**.

The processing order is deliberately:

```text
RECEIVE
   |
   v
GENERATE EVENT ID
   |
   v
CALCULATE HASH
   |
   v
STORE RAW EVENT
   |
   v
PARSE
```

The system must never follow:

```text
RECEIVE
   |
   v
PARSE
   |
   v
STORE
```

because an unexpected parser failure could result in loss of forensic evidence.

Every incoming event receives:

```text
event_id
source_id
ingestion_timestamp
raw_hash
payload_encoding
raw_payload
```

The raw payload is stored in immutable object storage.

---

# 7. Raw Storage

## Technology

**MinIO with S3-compatible object storage**

Raw events are stored separately from metadata and analytics data.

Example storage structure:

```text
/raw/
    year=2026/
        month=08/
            day=31/
                source=firewall-001/
                    hour=10/
                        batch-001
                        batch-002
```

Raw storage provides:

* Lossless preservation
* Long-term retention
* Forensic retrieval
* Data lake compatibility
* Low-cost storage
* Horizontal scalability

---

# 8. Event Format Detection

ULPF automatically determines the format of incoming data.

Supported formats include:

* Syslog
* JSON
* XML
* CSV
* CEF
* LEEF
* key-value
* regular-expression-based proprietary formats

Example detection rules:

```text
<PRI>                  -> Syslog
CEF:0|                 -> CEF
LEEF:2.0|              -> LEEF
{ ... }                -> JSON
<xml ...>              -> XML
CSV structure          -> CSV
key=value pairs        -> Key-Value
otherwise              -> Registered custom parser
```

Format detection is deterministic and lightweight.

An AI/LLM is not required for the high-volume event-processing path.

---

# 9. Source Identification

After determining the format, ULPF identifies the source.

Example:

```text
Vendor: Palo Alto Networks
Product: PAN-OS
Device Type: Firewall
Format: Syslog
```

or:

```text
Vendor: Cisco
Product: ASA
Device Type: Firewall
Format: Syslog
```

The source registry stores:

```text
source_id
vendor
product
device_type
format
parser_id
parser_version
schema_version
status
```

---

# 10. Parser Engine

The parser engine is responsible for extracting source-specific attributes.

The design deliberately avoids writing a separate application class for every vendor.

Instead, ULPF uses a:

> **Configuration-driven generic parser engine.**

A parser definition describes:

* Source format
* Field extraction rules
* Field types
* Transformations
* Validation rules
* Normalization mappings

Example:

```yaml
parserId: paloalto-pan
format: key-value

fields:

  - source: src
    target: source.ip
    type: ip

  - source: dst
    target: destination.ip
    type: ip

  - source: action
    target: event.action
    type: string

  - source: protocol
    target: network.transport
    type: string
```

The generic parser engine executes these definitions.

---

# 11. Intermediate Representation

The parser first produces an intermediate representation.

Example input:

```text
src=10.10.1.25
dst=172.16.2.10
action=deny
protocol=tcp
```

Intermediate representation:

```json
{
  "src": "10.10.1.25",
  "dst": "172.16.2.10",
  "action": "deny",
  "protocol": "tcp"
}
```

The intermediate representation separates vendor-specific parsing from universal normalization.

---

# 12. Common Event Taxonomy

ULPF normalizes heterogeneous fields into a common taxonomy.

The cybersecurity vocabulary will be based primarily on:

> **OCSF (Open Cybersecurity Schema Framework)**

ULPF will maintain its own extension namespace for fields that are not adequately represented by the selected OCSF structures.

This provides:

* Vendor independence
* Cybersecurity interoperability
* Standardized analytics
* Consistent field naming
* SIEM compatibility
* ML compatibility

---

# 13. Normalization

Different vendors may represent the same concept differently.

For example:

```text
src
sourceAddress
src_ip
source-ip
client_ip
```

all represent the source IP address.

ULPF maps them to:

```text
source.ip
```

Similarly:

```text
dst
destinationAddress
dst_ip
destination-ip
server_ip
```

become:

```text
destination.ip
```

The normalized event could look like:

```json
{
  "event_id": "01JABC...",
  "timestamp": "2026-08-31T10:15:23Z",

  "source": {
    "id": "firewall-001",
    "vendor": "Palo Alto Networks",
    "product": "PAN-OS",
    "type": "firewall"
  },

  "event": {
    "category": "network",
    "type": "connection",
    "action": "deny"
  },

  "source_network": {
    "ip": "10.10.1.25",
    "port": 443
  },

  "destination_network": {
    "ip": "172.16.2.10",
    "port": 52144
  },

  "network": {
    "transport": "tcp"
  },

  "ulpf": {
    "raw_event_id": "01JABC...",
    "raw_hash": "sha256:...",
    "parser_id": "paloalto-pan",
    "parser_version": "1.2",
    "mapping_version": "4",
    "schema_version": "1.0"
  }
}
```

---

# 14. Validation

Every normalized event passes through validation.

Validation checks:

* Required fields
* Data types
* IP address validity
* Timestamp validity
* Port ranges
* Enumerated values
* Schema compatibility
* Parser version compatibility

Invalid fields should not cause the entire raw event to disappear.

Instead:

```text
Raw Event
    |
    +----> Valid normalized event
    |
    +----> Partial/failed parsing record
    |
    +----> Original raw event retained
```

---

# 15. Traceability

Traceability is maintained between:

```text
Normalized Event
       |
       v
ULPF Event ID
       |
       v
Raw Event ID
       |
       v
Original Raw Payload
```

Every normalized event stores:

```text
event_id
raw_event_id
raw_hash
source_id
parser_id
parser_version
mapping_version
schema_version
```

This enables:

* Forensic investigation
* Compliance auditing
* Parser debugging
* Reprocessing
* Data lineage
* Integrity verification

---

# 16. Parser Versioning

Parser definitions are version controlled.

Example:

```text
paloalto-pan
    |
    +-- v1.0
    +-- v1.1
    +-- v1.2
```

Mapping definitions are also versioned:

```text
mapping_version = 4
```

This ensures that an event processed six months ago can still be understood in terms of the exact parser and mapping logic used at that time.

---

# 17. Plug-and-Play Source Onboarding

ULPF provides a source onboarding workflow.

```text
Upload Sample Log
        |
        v
Format Detection
        |
        v
Source Information
        |
        v
Field Extraction
        |
        v
Field Mapping
        |
        v
Normalized Preview
        |
        v
Validation
        |
        v
Save Parser Definition
        |
        v
Activate Source
```

The administrator does not need to modify the core application code for ordinary parser additions.

---

# 18. Parser Onboarding UI

The UI should provide:

### Step 1 — Upload sample

```text
sample.log
```

### Step 2 — Detect format

```text
Detected Format: Syslog
```

### Step 3 — Extract fields

```text
src
dst
action
protocol
srcPort
dstPort
```

### Step 4 — Map fields

```text
src       → source.ip
dst       → destination.ip
action    → event.action
protocol  → network.transport
```

### Step 5 — Preview

```text
Input
   ↓
Parser
   ↓
Normalized Output
```

### Step 6 — Activate

```text
Parser: paloalto-pan
Version: 1.0
Status: ACTIVE
```

---

# 19. Unified Enterprise Visibility

Once all events use the common taxonomy, the same analytics layer can query:

```text
Firewalls
Routers
IDS
IPS
VPN
Proxies
Servers
Endpoints
Applications
Cloud systems
```

without requiring source-specific dashboards.

The dashboard can display:

* Events by vendor
* Events by device
* Events by severity
* Events by event category
* Top source IPs
* Top destination IPs
* Denied traffic
* Allowed traffic
* Authentication failures
* Parser failures
* Unknown sources
* Events per second
* Event trends

---

# 20. SIEM Integration

ULPF exposes normalized events through Kafka.

Example topics:

```text
ulpf.raw.events
ulpf.parsed.events
ulpf.normalized.events
ulpf.failed.events
ulpf.siem.events
```

Downstream SIEM platforms can consume:

```text
ulpf.normalized.events
```

without needing to understand the original vendor-specific formats.

This reduces the parsing burden on downstream security systems.

---

# 21. Data Lake Integration

Normalized events can be written as:

```text
Parquet
```

to S3-compatible object storage.

Example:

```text
/events/
    year=2026/
        month=08/
            day=31/
                hour=10/
```

Partitioning enables efficient analytical processing.

The resulting data can be consumed by:

* Apache Spark
* Trino
* Presto
* ML pipelines
* Data warehouse systems
* Forensic analysis tools

---

# 22. Analytics Database

## Technology

**ClickHouse**

ClickHouse stores normalized events for high-speed analytical queries.

Example:

```sql
SELECT
    source_ip,
    destination_ip,
    count(*)
FROM events
WHERE timestamp > now() - INTERVAL 1 HOUR
GROUP BY source_ip, destination_ip;
```

Potential analytics include:

```text
Events per second
Events per vendor
Events per device
Top source IPs
Top destination IPs
Denied connections
Allowed connections
Traffic by protocol
Traffic by port
Severity distribution
Anomaly trends
```

---

# 23. AI/ML Readiness

ULPF does not place an LLM in the high-volume event-processing path.

Instead, normalized events are converted into ML-ready structured features.

Examples:

```text
events_per_minute
unique_destinations
failed_connections
connection_frequency
port_distribution
source_frequency
destination_frequency
bytes_transferred
event_severity
```

These features can be used for:

* Anomaly detection
* Threat detection
* Behavioral analytics
* Operational anomaly detection
* Predictive analytics
* Machine learning pipelines

---

# 24. Local AI/ML Inference

For air-gapped deployments, models can be packaged locally.

The prototype can use:

> **ONNX Runtime**

Example:

```text
Normalized Events
       |
       v
Feature Extraction
       |
       v
ONNX Model
       |
       v
Anomaly Score
       |
       v
Dashboard / SIEM
```

No cloud AI API is required.

---

# 25. Why an LLM Is Not Used in the Event Hot Path

An LLM-based parser for every event would be inappropriate at billions-of-events-per-day scale.

Problems include:

* High latency
* High computational cost
* Non-deterministic output
* Difficult validation
* Privacy concerns
* Air-gap deployment complexity
* Poor throughput

Therefore:

```text
High-volume event path
        |
        v
Deterministic parsing
        |
        v
Deterministic normalization
```

An LLM may optionally assist administrators with parser creation during onboarding, but it is not required for event processing.

---

# 26. Core Technology Stack

## Backend

| Component      | Technology                                           |
| -------------- | ---------------------------------------------------- |
| Language       | Java 21                                              |
| Framework      | Spring Boot 4.x                                      |
| API            | REST                                                 |
| Build          | Maven                                                |
| Concurrency    | Java concurrency / virtual threads where appropriate |
| Serialization  | Jackson                                              |
| Validation     | Jakarta Validation                                   |
| Security       | Spring Security                                      |
| Authentication | JWT/OIDC-compatible architecture                     |

---

# 27. Streaming Layer

## Apache Kafka

Kafka is used as the event-streaming backbone.

Responsibilities:

* Event buffering
* Partitioning
* Replay
* Consumer groups
* Backpressure
* Horizontal scaling
* Durable event transport

Example:

```text
Source
  |
  v
ULPF
  |
  v
Kafka
  |
  +--> Parser processing
  |
  +--> Analytics
  |
  +--> SIEM
  |
  +--> Data lake
```

---

# 28. Metadata Database

## PostgreSQL

PostgreSQL stores:

```text
users
roles
sources
vendors
products
parser_definitions
parser_versions
mapping_rules
mapping_versions
schemas
onboarding_requests
output_destinations
pipeline_configuration
audit_records
```

PostgreSQL is **not** used as the primary storage system for billions of raw events.

---

# 29. Raw Event Storage

## MinIO

MinIO provides:

* S3-compatible object storage
* Air-gapped deployment
* Raw-event retention
* Data lake storage
* Forensic storage
* Horizontal scalability

---

# 30. Analytics Database

## ClickHouse

ClickHouse is used for:

* High-volume event analytics
* Aggregations
* Time-based queries
* Threat hunting
* Dashboards
* Operational analytics

---

# 31. Frontend

## React + TypeScript + Vite

The web interface provides:

```text
Dashboard
Source Management
Parser Management
Onboarding
Event Search
Event Details
Raw Event Viewer
Analytics
Pipeline Monitoring
System Configuration
```

---

# 32. Containerization

ULPF is packaged as a Docker image.

Example:

```text
ulpf:1.0.0
```

Supporting infrastructure can be packaged using Docker Compose for the prototype.

Example:

```text
docker-compose.yml
```

with:

```text
ULPF
Kafka
PostgreSQL
ClickHouse
MinIO
```

All components can run within an isolated environment.

---

# 33. Requirement Mapping: (a)–(k)

## (a) Preserve complete raw event data without information loss

### Solution

ULPF stores the original payload before parsing.

Each event has:

```text
raw_event_id
raw_payload
raw_hash
source_id
timestamp
encoding
```

The original event is retained even when parsing fails.

Therefore:

```text
Unknown Event
     |
     +----> Raw Event Preserved
     |
     +----> Failed Parsing Record
```

No raw event is discarded merely because its format is unsupported.

### Requirement satisfied: YES

---

# 34. (b) Extract and parse source-specific attributes

### Solution

ULPF contains a configuration-driven parser engine supporting:

* Syslog
* JSON
* XML
* CSV
* CEF
* LEEF
* key-value
* regex
* proprietary formats

Parser definitions specify how source-specific fields are extracted.

Example:

```text
src       → source.ip
dst       → destination.ip
action    → event.action
protocol  → network.transport
```

### Requirement satisfied: YES

---

# 35. (c) Normalize fields into a common event taxonomy

### Solution

ULPF uses a common cybersecurity taxonomy based primarily on OCSF, with ULPF-specific extensions.

Different vendor fields are mapped into common fields.

Example:

```text
sourceAddress
src_ip
client_ip
src
```

all map to:

```text
source.ip
```

### Requirement satisfied: YES

---

# 36. (d) Maintain traceability between normalized and original events

### Solution

Each normalized event contains:

```text
event_id
raw_event_id
raw_hash
source_id
parser_id
parser_version
mapping_version
schema_version
```

Therefore:

```text
Normalized Event
      |
      v
Raw Event ID
      |
      v
Original Raw Event
```

### Requirement satisfied: YES

---

# 37. (e) Plug-and-play onboarding of new log sources

### Solution

New sources can be onboarded through the UI:

```text
Upload Sample
     |
Format Detection
     |
Field Extraction
     |
Field Mapping
     |
Preview
     |
Validation
     |
Activation
```

Parser definitions are stored externally from the application code.

Therefore ordinary new-source onboarding does not require recompiling the application.

### Requirement satisfied: YES

---

# 38. (f) Unified visibility across enterprise environments

### Solution

All sources are transformed into the same normalized schema.

This allows one analytics layer to query:

```text
Firewall
Router
IDS
IPS
VPN
Proxy
Server
Endpoint
Cloud
```

using the same field definitions.

### Requirement satisfied: YES

---

# 39. (g) Efficient SIEM and Data Lake integration

### Solution

ULPF provides:

### Streaming output

```text
Kafka
    |
    v
SIEM
```

### Data lake output

```text
Normalized Events
       |
       v
Parquet
       |
       v
MinIO / S3
```

This allows downstream systems to consume already-normalized events.

### Requirement satisfied: YES

---

# 40. (h) AI/ML-ready security and operational analytics

### Solution

ULPF produces structured normalized events and derived features.

Example features:

```text
events_per_minute
unique_destinations
failed_connections
port_distribution
source_frequency
destination_frequency
bytes_transferred
```

These can be consumed by machine learning models.

Local ONNX inference can be used for air-gapped anomaly detection.

### Requirement satisfied: YES

---

# 41. (i) Reduced parser development effort

### Solution

ULPF uses a generic parser engine with declarative parser definitions.

Instead of:

```text
Vendor A → Java code
Vendor B → Java code
Vendor C → Java code
Vendor D → Java code
```

ULPF uses:

```text
Generic Parser Engine
        +
Vendor Parser Definition
        +
Mapping Definition
```

This dramatically reduces source onboarding effort.

### Requirement satisfied: YES

---

# 42. (j) Air-gapped deployment

### Solution

ULPF has no mandatory runtime dependency on public cloud services or the internet.

The complete deployment can contain:

```text
ULPF Docker image
Kafka
PostgreSQL
ClickHouse
MinIO
Parser definitions
ML models
Frontend assets
```

All dependencies can be installed in an isolated network.

No external:

```text
Cloud API
LLM API
CDN
Threat intelligence API
```

is required.

### Requirement satisfied: YES

---

# 43. (k) Containerized/platform-independent deployment

### Solution

ULPF is distributed as a Docker container.

The application can run using:

```text
Docker
Docker Compose
Kubernetes
Private Container Registry
Air-gapped infrastructure
```

The application itself remains unchanged across deployment environments.

### Requirement satisfied: YES

---

# 44. Scaling to Billions of Events per Day

The system is designed for horizontal scaling.

## 44.1 Event-rate calculation

1 billion events/day is approximately:

```text
11,574 events/second
```

10 billion events/day is approximately:

```text
115,741 events/second
```

The architecture therefore cannot rely on a single process or synchronous database writes.

---

# 45. Kafka Partitioning

Kafka partitions the event stream.

Example:

```text
ulpf.raw.events

Partition 0
Partition 1
Partition 2
...
Partition 63
```

ULPF application instances consume partitions using consumer groups.

Multiple instances can run the same ULPF image:

```text
              ULPF Application
                    |
       +------------+------------+
       |            |            |
       v            v            v
    Instance 1   Instance 2   Instance 3
       |            |            |
       +------------+------------+
                    |
                    v
             Same ULPF Pipeline
```

This is horizontal scaling of the same application rather than a microservice architecture.

---

# 46. Stateless Processing

The core event pipeline is designed to be mostly stateless:

```text
Ingest
  |
Parse
  |
Normalize
  |
Validate
  |
Route
```

Configuration and persistent metadata are stored in PostgreSQL.

Therefore additional ULPF instances can be started without redesigning the processing pipeline.

---

# 47. Backpressure

Kafka acts as a durable buffer.

If ClickHouse or another downstream component temporarily slows down:

```text
Sources
   |
   v
Kafka
   |
   v
ULPF
```

events remain in Kafka until processing catches up.

This prevents downstream slowdowns from immediately causing event loss.

---

# 48. Batch Database Writes

ULPF does not write every event individually.

Instead:

```text
Event
Event
Event
Event
Event
   |
   v
Batch Buffer
   |
   v
Bulk Insert
   |
   v
ClickHouse
```

Batching reduces database overhead and increases throughput.

---

# 49. Columnar Analytics

ClickHouse stores normalized events in columnar form.

This is highly efficient for analytical queries involving:

* timestamps
* IP addresses
* ports
* event categories
* actions
* vendors
* devices
* aggregations

---

# 50. Data Compression

Raw and normalized data use appropriate compression.

The data lake uses compressed columnar formats such as:

```text
Parquet
```

This reduces storage requirements and improves analytical scan performance.

---

# 51. Air-Gapped Deployment

Air-gapped deployment is a first-class architectural requirement.

The system must be capable of operating with:

```text
Internet = OFF
```

The runtime environment contains all required software and models.

---

# 52. Air-Gapped Package

A deployment bundle can contain:

```text
ULPF/
│
├── docker-compose.yml
│
├── images/
│   ├── ulpf.tar
│   ├── kafka.tar
│   ├── postgres.tar
│   ├── clickhouse.tar
│   └── minio.tar
│
├── parsers/
│   ├── generic-syslog.yaml
│   ├── cisco-asa.yaml
│   ├── paloalto.yaml
│   └── fortigate.yaml
│
├── models/
│   └── anomaly-model.onnx
│
└── documentation/
```

The images can be imported into an internal/private container registry.

---

# 53. No Runtime Cloud Dependency

The following are deliberately avoided in the core processing pipeline:

```text
OpenAI API
Azure AI APIs
Google Cloud APIs
External SaaS
Public CDN
External threat-intelligence APIs
```

This ensures operation inside a completely isolated environment.

---

# 54. Container Deployment

For the hackathon, Docker Compose is sufficient.

Example logical deployment:

```text
+------------------------------------------------+
|              Air-Gapped Network                |
|                                                |
|  +------------------------------------------+  |
|  |              ULPF Application            |  |
|  |                                          |  |
|  | Ingestion → Parser → Normalize → Output |  |
|  +------------------------------------------+  |
|             |       |       |                 |
|             v       v       v                 |
|          Kafka   PostgreSQL ClickHouse        |
|                                  |             |
|                                  v             |
|                               MinIO            |
|                                                |
+------------------------------------------------+
```

For production-scale environments, Kubernetes can be introduced for orchestration.

---

# 55. Blockchain / Tamper-Evidence Consideration

The problem is categorized under Blockchain & Cybersecurity.

ULPF should not introduce blockchain merely for the sake of using blockchain.

Instead, the useful blockchain-related capability is **tamper evidence and data integrity**.

Each raw event receives a SHA-256 hash:

```text
Event 1 → Hash 1
Event 2 → Hash 2
Event 3 → Hash 3
```

Events can be grouped into batches and represented through a Merkle tree:

```text
              Merkle Root
              /         \
          Hash A       Hash B
          /   \        /   \
       Hash1 Hash2  Hash3 Hash4
```

The Merkle root can be stored with:

```text
batch_id
timestamp
event_count
merkle_root
```

This provides evidence if the underlying raw data is modified.

A full blockchain network is intentionally avoided because it would:

* Increase system complexity
* Add another distributed subsystem
* Increase operational overhead
* Not directly solve log normalization
* Reduce hackathon prototype reliability

The primary goal remains secure, lossless, traceable log processing.

---

# 56. Security Architecture

ULPF should provide:

* Authentication
* Role-based authorization
* Secure API endpoints
* TLS for network communication
* Encryption at rest where required
* Audit logging
* Parser integrity checks
* Raw-event hashing
* Configuration versioning

Suggested roles:

```text
ADMIN
SECURITY_ANALYST
OPERATOR
VIEWER
```

---

# 57. Failure Handling

A failed parser must never result in silent data loss.

Example:

```text
Incoming Event
      |
      v
Raw Stored
      |
      v
Parser
      |
      +------ SUCCESS ------> Normalize
      |
      +------ FAILURE ------> Failed Event
                              |
                              v
                         Raw Preserved
```

Failed events contain:

```text
event_id
raw_event_id
parser_id
parser_version
failure_reason
timestamp
```

This allows administrators to fix a parser and reprocess historical events.

---

# 58. Replay and Reprocessing

Because raw events are preserved and Kafka provides durable event streams, ULPF can support reprocessing.

Example:

```text
Raw Event
   |
   v
Old Parser v1
   |
   v
Normalized Event
```

After fixing the parser:

```text
Same Raw Event
   |
   v
Parser v2
   |
   v
Improved Normalized Event
```

The raw event remains unchanged.

---

# 59. Hackathon Prototype Scope

The prototype should demonstrate the architecture without attempting to implement every enterprise-scale feature.

## Essential prototype features

### 1. Ingestion

Implement:

```text
Syslog
HTTP
File Upload
```

---

### 2. Multiple formats

Demonstrate:

```text
Syslog
JSON
CEF
LEEF
CSV
```

---

### 3. Parser engine

Implement a generic configuration-driven parser.

---

### 4. Sample vendor parsers

Demonstrate several perimeter-device sources, such as:

```text
Cisco ASA
Palo Alto
Fortinet
Generic Syslog
Generic JSON
CEF
LEEF
```

The goal is to demonstrate extensibility rather than support hundreds of vendors.

---

### 5. Source onboarding

Implement:

```text
Upload Sample
→ Detect
→ Extract
→ Map
→ Preview
→ Activate
```

---

### 6. Universal normalization

Demonstrate different vendor formats producing the same normalized schema.

---

### 7. Raw-event traceability

Provide a UI where:

```text
Normalized Event
       |
       v
View Original
       |
       v
Exact Raw Event
```

---

### 8. Analytics dashboard

Implement:

```text
Events/sec
Events by vendor
Events by device
Top source IPs
Top destination IPs
Denied traffic
Parser failures
Unknown sources
```

---

### 9. Failure handling

Demonstrate an unknown log format.

The system should show:

```text
UNKNOWN FORMAT
      |
      v
RAW PRESERVED
      |
      v
FAILED / UNKNOWN QUEUE
```

---

### 10. Kafka integration

Use Kafka for the streaming pipeline even if the hackathon demonstration uses a smaller dataset.

---

### 11. ClickHouse analytics

Use ClickHouse to demonstrate high-volume analytical queries.

---

### 12. MinIO raw storage

Use MinIO to demonstrate lossless raw-event retention.

---

### 13. Docker deployment

The entire prototype should start through Docker Compose.

---

# 60. Features Explicitly Deferred

The following should not be allowed to consume excessive hackathon development time.

## Hundreds of vendor parsers

Instead:

```text
5–7 representative parsers
+
generic parser framework
```

---

## Complete enterprise SIEM

ULPF is a preprocessing and normalization framework.

It should integrate with SIEM rather than attempt to completely replace one.

---

## Large-scale AI model training

The requirement is AI/ML readiness.

The prototype does not need to train a foundation model.

A lightweight anomaly-detection demonstration is sufficient.

---

## LLM-based hot-path parsing

LLMs should not process every log event.

Deterministic parsers are more suitable.

---

## Full blockchain network

Do not build an unnecessary blockchain ecosystem.

Use hashing/Merkle-based tamper evidence if time permits.

---

## Production Kubernetes platform

For the hackathon:

```text
Docker Compose
```

is sufficient.

The architecture should remain Kubernetes-compatible for future deployment.

---

# 61. Final Architecture

The final system is:

```text
                    +-------------------+
                    |   Log Sources     |
                    +---------+---------+
                              |
                              v
                    +-------------------+
                    | ULPF APPLICATION   |
                    |                   |
                    | Ingestion         |
                    | Format Detection  |
                    | Source Detection  |
                    | Parser Engine     |
                    | Normalization     |
                    | Validation        |
                    | Traceability      |
                    | Routing           |
                    | Analytics API     |
                    | Onboarding        |
                    +---------+---------+
                              |
                +-------------+-------------+
                |             |             |
                v             v             v
             MinIO          Kafka       ClickHouse
            Raw Store      Streams       Analytics
                |             |             |
                |             |             |
                |             v             |
                |           SIEM            |
                |                           |
                +-------------+-------------+
                              |
                              v
                         React UI
                              |
                              v
                   Analysts / Operators
```

---

# 62. Technology Decision Summary

| Layer                    | Technology                | Primary Reason                               |
| ------------------------ | ------------------------- | -------------------------------------------- |
| Application              | Java 21                   | High-throughput enterprise backend           |
| Framework                | Spring Boot               | Mature, modular and production-ready         |
| Architecture             | Modular Monolith          | One coherent deployable application          |
| Streaming                | Apache Kafka              | High throughput, replay and partitioning     |
| Metadata                 | PostgreSQL                | Reliable relational configuration/versioning |
| Raw storage              | MinIO/S3                  | Scalable immutable object storage            |
| Analytics                | ClickHouse                | High-performance analytical queries          |
| Schema                   | OCSF + ULPF extensions    | Common cybersecurity taxonomy                |
| Frontend                 | React + TypeScript + Vite | Fast, maintainable dashboard development     |
| API                      | REST                      | Simple integration and interoperability      |
| ML                       | ONNX Runtime              | Local, air-gap-compatible inference          |
| Packaging                | Docker                    | Platform-independent deployment              |
| Prototype orchestration  | Docker Compose            | Simple reproducible deployment               |
| Production orchestration | Kubernetes                | Optional future horizontal orchestration     |

---

# 63. Architecture Trade-offs

## Modular Monolith vs Microservices

### Chosen

Modular monolith.

### Why

The requirement asks for one unified application. A modular monolith provides clear internal separation while keeping:

* One repository
* One application
* One deployment artifact
* Simpler debugging
* Simpler testing
* Easier air-gapped deployment

### Rejected

Microservices.

Microservices would introduce:

* Multiple deployments
* Service discovery
* Network communication
* More configuration
* More operational complexity
* More failure points

That complexity is unnecessary for the hackathon and does not directly solve the problem.

---

# 64. Kafka vs RabbitMQ

## Chosen

Apache Kafka.

### Reason

ULPF needs:

* Massive event throughput
* Partitioning
* Durable event streams
* Replay
* Consumer groups
* Backpressure

Kafka is better aligned with those requirements.

### Rejected

RabbitMQ.

RabbitMQ is excellent for traditional message queues but is less appropriate as the primary backbone for a massive event-streaming and replay-oriented architecture.

---

# 65. ClickHouse vs PostgreSQL for Events

## Chosen

ClickHouse.

PostgreSQL remains the metadata database.

### Reason

Billions of events require:

* Columnar storage
* Compression
* Fast aggregation
* Distributed analytical querying

ClickHouse is specifically optimized for these workloads.

### Rejected

Using PostgreSQL for all event data would make the analytical workload unnecessarily expensive and difficult to scale.

---

# 66. MinIO vs PostgreSQL for Raw Events

## Chosen

MinIO.

### Reason

Raw data is:

* Large
* Immutable
* Long-lived
* Object-oriented
* Often retrieved by event/batch rather than relational joins

Object storage is therefore more appropriate.

### Rejected

Storing raw payloads directly in PostgreSQL would create unnecessary database storage and scaling pressure.

---

# 67. LLM Parsing vs Deterministic Parsing

## Chosen

Deterministic parser engine.

### Reason

The hot path needs:

* Predictability
* Low latency
* High throughput
* Repeatability
* Validation
* Air-gap compatibility

### Rejected

LLM-based parsing for every event.

It would be too expensive, slow and nondeterministic at billions-of-events-per-day scale.

LLMs can optionally assist administrators during parser creation but should not be required for production ingestion.

---

# 68. Full Blockchain vs Tamper Evidence

## Chosen

SHA-256 hashes with optional Merkle-tree-based batch integrity.

### Reason

This directly addresses:

* Data integrity
* Forensic confidence
* Tamper detection

without unnecessarily introducing blockchain infrastructure.

### Rejected

A complete blockchain network.

It does not provide enough additional value to justify the operational and architectural complexity for the core ULPF problem.

---

# 69. Final Design Principle

The most important architectural principle of ULPF is:

```text
             PRESERVE FIRST
                   |
                   v
               PARSE SECOND
                   |
                   v
             NORMALIZE THIRD
                   |
                   v
             ANALYZE FOURTH
```

The system never sacrifices the original event for the sake of normalization.

The final architecture therefore provides:

```text
Any Source
     ↓
Any Format
     ↓
Lossless Raw Preservation
     ↓
Generic Source Parser
     ↓
Common Cybersecurity Taxonomy
     ↓
Traceable Normalized Event
     ↓
SIEM + Data Lake + Analytics
     ↓
AI/ML
```

while remaining:

```text
Scalable
Extensible
Vendor-Agnostic
Air-Gap Compatible
Containerized
Forensically Traceable
Analytics-Ready
```

---

# 70. Final One-Line Architecture Statement

> **ULPF is a horizontally scalable, configuration-driven modular monolith built with Java 21 and Spring Boot that losslessly preserves heterogeneous log events in MinIO, streams them through Kafka for high-throughput processing, parses and normalizes them into an OCSF-based common taxonomy, maintains cryptographically verifiable raw-to-normalized traceability, stores analytics-ready events in ClickHouse, exposes SIEM/Data Lake integrations, supports local AI/ML inference, enables plug-and-play source onboarding, and runs entirely inside an air-gapped Docker-based environment.**

## Model 3: ## Overall Architecture

The Universal Log Pre-processing Framework (ULPF) operates as a high-throughput, unified streaming application designed for zero-data-loss log ingestion, normalization, and routing.

```
[ Ingestion Layer ]        [ Core Processing Pipeline ]             [ Storage & Egress Layer ]
-------------------        ----------------------------             --------------------------
Syslog (UDP/TCP/TLS) ---\                                           /--> Parquet (Raw + Normalized)
Logstash Beats / HTTP ---> [ Netty Ingestion Engine ]              /     [Cold/Warm Air-Gapped Lake]
Custom Socket Agents ---/           |                             /
                                    v                            /-----> Local Search / Hot Index
                            [ Apache Flink Engine ] ------------<        (ClickHouse Vector DB)
                             - Step 1: Raw Hash & Enriched Metadata     \
                             - Step 2: Parser Routing & Field Extraction \--> Low-Latency Egress
                             - Step 3: Schema Mapping (Common Taxonomy)   \    (SIEM / Kafka / Syslog)
                             - Step 4: Lineage Mapping & Schema Validation

```

1. **Ingestion Layer:** Raw logs arrive via multiple network protocol listeners (Syslog over UDP/TCP/TLS, HTTP Bulk API, and Filebeat/Beats framing) managed by a High-Performance Async I/O layer.
2. **Streaming Execution Engine:**
* **Raw Event Ledgering (a, d):** The raw payload is immediately captured, timestamped with nano-precision, hashed (`SHA-256`), and tagged with a unique 128-bit `ulpf_event_id` (ULID).
* **Parser Routing & Field Extraction (b, i):** A dynamic schema router identifies the log format using header signatures or source IP/port mapping, delegating extraction to declarative configuration schemas (YAML/RE2).
* **Taxonomy Normalization (c):** Extracted key-value pairs are projected onto a Common Event Taxonomy (spanning identity, network, device, process, and security context).
* **Validation & Fallback:** Unmapped or malformed attributes are preserved in an unindexed overflow field (`raw_unmapped`) to ensure zero information loss.


3. **Storage & Egress Engine:**
* **Hot Path (g, h):** High-efficiency streaming sinks write normalized records into a fast local column-store for real-time querying, vector indexing, and AI/ML model inference.
* **Cold/Warm Path (a, g, j):** Batched events are written directly to local NVMe-backed Parquet/Iceberg storage partitioned by `year/month/day/hour/source_type`.
* **Egress (g):** Forwarding mechanisms push normalized JSON or Syslog payloads to external SIEMs (e.g., Splunk, Chronicle, Elastic) via configurable TCP/HTTP sockets.



---

## Technical Stack & Justification

To maintain performance, simplicity, and ease of deployment within a unified codebase, ULPF is implemented in **Rust**.

* **Core Runtime & Streaming Engine:** **Rust (Tokio async framework + Apache Arrow DataFusion / Flink-Rust ecosystem)**
* *Justification:* Rust guarantees memory safety without Garbage Collection (GC) pauses, which cause predictable micro-stutters in Java/Go log forwarders at scale. It offers low CPU and RAM consumption per event stream.
* *Alternatives Rejected:* Java/Logstash (high memory overhead, GC pauses at high throughput), Go (GC overhead under multi-gigabyte memory allocations), Python (insufficient throughput for billions of events/day).


* **Ingestion Layer:** **Netty-style Async Network Sinks (Rust Tokio-Net / Hyper)**
* *Justification:* Non-blocking I/O allows a single application instance to sustain thousands of simultaneous TCP/Syslog and HTTP connections.


* **Declarative Parsing & Rule Engine:** **RE2 / Vector-style VRL Engine + WASM plugin interface**
* *Justification:* Enables runtime configuration updates without application restarts. WebAssembly (WASM) sandboxing allows edge-case, custom transformation plugins to be loaded securely in air-gapped environments without recompiling the application core.


* **Hot Storage & Analytics Engine:** **ClickHouse (Embedded/Standalone within container space)**
* *Justification:* Provides high compression ratios (up to 10:1 on structured logs) and fast vectorized SQL query performance for billion-row datasets.
* *Alternatives Rejected:* Elasticsearch (excessive RAM usage and disk amplification), PostgreSQL (scales poorly for multi-terabyte daily ingest).


* **Cold Storage & Data Lake Format:** **Apache Parquet via Apache Arrow**
* *Justification:* Standardized columnar storage format. Zero-copy transformations directly from raw memory to Arrow tables minimize CPU cycles before dumping to Parquet.


* **Packaging & Delivery:** **Single Static Binary wrapped in a Distroless OCI Docker Container**
* *Justification:* Complies directly with air-gap deployment requirements. The container image packs all dependencies, parsing schemas, and runtime binaries into a small footprint (<100MB).



---

## Architectural Mapping: Points (a) through (k)

| Requirement Point | Architectural Solution & Implementation Mechanics |
| --- | --- |
| **a) Preserve Raw Event Data** | Raw payloads are stored intact inside the `raw_payload` byte array field alongside a SHA-256 cryptographic hash (`raw_hash`). No field truncation or destruction occurs during parsing. |
| **b) Extract Source Attributes** | Declarative YAML rules use zero-copy regex (RE2) and key-value splitting to isolate source fields into structured JSON maps, preserving vendor-specific context. |
| **c) Normalize Field Taxonomy** | A predefined taxonomy map aligns diverse vendor keys into standard schema structures (e.g., `src_ip`, `dst_port`, `user_name`, `action`, `event_category`). |
| **d) Traceability & Lineage** | Every record receives a time-sortable 128-bit ULID (`ulpf_event_id`). The raw log and normalized view share this exact ID and hash link across all log destinations. |
| **e) Plug-and-Play Onboarding** | New log sources are added by dropping a declarative YAML file into a watched rules directory or loading a compiled WASM plugin at runtime without interrupting active streams. |
| **f) Unified Visibility** | ClickHouse views and standardized Arrow output present all multi-source enterprise logs under one consolidated schema interface for queries. |
| **g) SIEM & Data Lake Integration** | Native writers stream directly to Apache Parquet files (Data Lake) while simultaneous async sockets push normalized JSON/CEF feeds to downstream SIEM systems. |
| **h) AI/ML-Ready Analytics** | Parquet datasets export directly to Python/PyTorch/Spark via Apache Arrow memory vectors without needing custom ingestion/serialization pipelines. |
| **i) Reduced Parser Effort** | Modular configuration schemas use field inheritance and shared sub-patterns (e.g., standard IP/Timestamp regex blocks) to speed up parser development. |
| **j) Air-Gapped Deployment** | Self-contained design: zero external runtime network dependencies, local geo-IP/threat databases pre-bundled, offline-installable OCI container image. |
| **k) Containerized Packaging** | Built into a statically linked distroless Docker image capable of running on standard container runtimes (Docker, Podman, Kubernetes). |

---

## Handling "Billions of Events per Day" Scale

Handling 2 Billion events per day requires sustained processing of roughly **25,000 to 50,000 events per second (eps)** with peak bursts exceeding **100,000 eps**.

* **Lock-Free Concurrency & Zero-Copy Memory:**
* Incoming streams are received by dedicated worker threads via non-blocking ring buffers (`crossbeam-channel`).
* Log string manipulation relies on string slices (`&str`) pointing directly to initial memory buffers to eliminate object creation overhead.


* **Vectorized Parsing and Transformation:**
* Operations are batched into arrays of 2,048 records using Apache Arrow arrays, enabling CPU SIMD (Single Instruction, Multiple Data) optimizations during schema casting and transformation.


* **Backpressure Management:**
* If downstream storage experiences IO delays, internal ring buffers apply asynchronous backpressure up to the TCP connection layer, throttling incoming producers safely without dropping logs or crashing due to Out-Of-Memory (OOM) errors.



---

## Air-Gapped Deployment Strategy

1. **Zero External Dependencies:**
* All schema validation engines, parsing libraries, standard enrichment libraries (e.g., local MaxMind GeoIP `.mmdb` files), and runtimes are embedded directly inside the application binary during build time.


2. **Offline Configuration Management:**
* Parsers and taxonomies are supplied via local directory mounts containing version-controlled YAML files or pre-packaged local rule bundles.


3. **Container Delivery:**
* Packaged as a single offline OCI image tarball (`ulpf-core:v1.0.tar.gz`), deployable across isolated environments via simple file transfers and `podman load` or `docker load`.



---

## Hackathon Prototype Scope vs. Production System

### What is Explicitly Left Out of the Hackathon Prototype

* **Distributed Clustering Orchestrator:** The prototype operates as a multi-threaded, high-throughput standalone node rather than a complex distributed cluster coordinator.
* **Advanced Machine Learning Inference Engine:** Live online model training is excluded; the prototype provides parquet/Arrow data feeds and lightweight rule-based anomaly detection hooks instead.
* **Complex Enterprise Identity Management (SAML/OAuth2):** Auth features are reduced to basic API keys and local TLS certificates for ingestion endpoints.
* **Dynamic GUI Schema Editor:** Parsers are updated by editing standard YAML files directly rather than through a complex web interface.

### Essential Core Included in the Prototype

* Fully functional high-speed UDP/TCP/HTTP ingestion engine.
* Complete implementation of raw preservation, hashing, ULID lineage assignment, and field parsing.
* Dynamic YAML rule engine supporting Syslog, JSON, CSV, and CEF formats out of the box.
* Parquet cold-path writer and an embedded local ClickHouse/Arrow hot query path demonstrating unified taxonomy queries.
* Fully containerized, air-gap-ready Docker image build script.

## Model 4: # Universal Log Pre-processing Framework (ULPF) – Single Application Design
**Smart India Hackathon 2026 | PS ID 26156 | NTRO**

---

## 1. Overall Architecture (End-to-End Data Flow)
┌─────────────────────────────────────────────────────────────────────────────────┐
│ INGESTION LAYER │
│ (Single gRPC server with a pluggable "Source Adapter" interface) │
│ - Active listeners: TCP/UDP syslog, HTTPS (JSON/XML/CEF/LEEF), file tailers │
│ - Each raw event wrapped in a common RawEvent envelope with: │
│ * source_type, source_id, arrival_timestamp, raw_bytes, encoding │
└─────────────────────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ PARSING PIPELINE │
│ (Chain-of-Responsibility of FormatDetector → SchemaMatcher → Parser) │
│ - Format detection: heuristic + ML-assisted fingerprinting │
│ - Parser plugins: JSON, Syslog, XML, CSV, CEF, LEEF, KV, regex, Grok │
│ - Output: structured ParsedEvent (JSON-like map, preserving all fields) │
└─────────────────────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ NORMALIZATION ENGINE │
│ (Rule-based + ML mapping to ULPF Common Event Taxonomy) │
│ - Uses a dynamic "Field Mapping Registry" (YAML-defined) │
│ - Maps vendor-specific fields → canonical names (e.g., src_ip, dst_ip) │
│ - Enriches with derived fields: geoip, threat intel, severity normalization │
│ - Output: NormalizedEvent conforming to a fixed Avro schema │
└─────────────────────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ TRACEABILITY & STORAGE │
│ (Single embedded database for metadata + pluggable object store for raw) │
│ - Raw events stored in a write-once, immutable blob store (local FS or S3) │
│ - Traceability: RawEvent hash stored in NormalizedEvent.original_hash │
│ - Metadata DB (RocksDB) holds: hash → location + parsing stats │
└─────────────────────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ OUTPUT ROUTER │
│ (Pluggable sinks: Kafka, Elasticsearch, Parquet files, Webhook) │
│ - Normalized events streamed to configured sinks for SIEM/Data Lake/ML │
│ - Supports exactly-once delivery with idempotent writes │
└─────────────────────────────────────────────────────────────────────────────────┘

**Key principle**: Single binary that runs a multi-threaded event loop. All components run in the same process, sharing in-memory caches and a unified configuration. No external orchestration required for basic operation.

---

## 2. Exact Tech Stack & Justification

| Component                | Choice                          | Justification & Trade-offs                                                                 |
|--------------------------|---------------------------------|----------------------------------------------------------------------------------------------|
| **Language**             | **Go 1.22+**                    | High concurrency (goroutines), low memory footprint, single-binary deployment, cross-compile for air-gap, excellent stdlib for net/io. Rejected Python due to GIL and larger memory; rejected Rust due to slower prototyping and steeper learning curve. |
| **RPC/Ingestion**        | **gRPC + Protocol Buffers**     | For internal high-throughput event envelopes; also supports HTTP/JSON fallback. More efficient than REST for billions of events. |
| **Parsing**              | **Go plugins + embedded regexp**| Dynamic loading of parser plugins without rebuilding; Grok patterns from logstash-patterns-core. Rejected external DSL (e.g., ANTLR) for simplicity. |
| **Normalization Rules**  | **YAML + CEL (Common Expression Language)** | Rules are human-readable and hot-reloadable. CEL allows dynamic field transforms without code changes. |
| **Raw Storage**          | **Local filesystem + optional S3-compatible** | Air-gap friendly; no external DB needed. Writes are sequential for performance. Rejected HDFS due to complexity in single-app mode. |
| **Metadata Index**       | **Embedded RocksDB (via gobadger)** | Lightning-fast key-value store; stores hash→location for traceability. Rejected SQLite for write throughput under high load. |
| **Output Sinks**         | **Kafka (for streaming) + Elasticsearch (for SIEM) + Parquet (for Data Lake)** | Standards-based; all supported via client libs. Can be disabled if air-gapped. |
| **Packaging**            | **Docker + single binary**      | Meets container requirement; also provide .tar.gz for air-gap installs. |
| **Configuration**        | **HCL (HashiCorp Config Language)** | Cleaner than JSON/YAML for complex nested config; supports variables and includes. |
| **Monitoring**           | **Embedded Prometheus metrics + health endpoint** | Lightweight, can be scraped even in air-gap. |

---

## 3. How Each Expected Solution (a–k) Is Satisfied

### a) Preserve complete raw event data without information loss
- Each incoming event is stored **verbatim** (original bytes + encoding info) in a raw blob store before any parsing.
- The raw blob is never modified; only a reference is carried forward.
- A configurable retention policy (time/size) can be applied to the raw store, but default is "keep all".

### b) Extract and parse source-specific attributes
- Parser plugins are source-aware (e.g., `cisco-asa`, `aws-vpc`, `json-app`).
- Each parser extracts **all** fields present; nothing is discarded.
- Parsed output is a flat or nested JSON map preserving vendor-native field names.

### c) Normalize fields into a common event taxonomy
- The Normalization Engine uses a **central Field Mapping Registry** (`mappings.yaml`) that defines:
  - Canonical taxonomy: `source_ip`, `destination_ip`, `user`, `event_type`, `severity`, `timestamp`, `protocol`, etc.
  - Vendor→Canonical mapping rules (e.g., `src`→`source_ip`, `sip`→`source_ip`).
- CEL expressions handle value transformations (e.g., severity strings → integer 0–10).
- All normalized events follow a fixed Avro schema to ensure consistency.

### d) Maintain traceability between normalized and original events
- Every `NormalizedEvent` contains a field `original_hash` (SHA-256 of raw bytes).
- A metadata index (`hash → raw_file_offset`) enables retrieval of the original event on demand.
- The `RawEvent` envelope also carries a unique `event_id` that is logged alongside normalized output.

### e) Plug-and-play onboarding of new log sources
- New sources are onboarded by:
  1. Dropping a **parser plugin** (Go plugin or compiled shared object) into the `parsers/` directory.
  2. Adding a **mapping rule** in `mappings.yaml` for that source type.
  3. Defining a **detector rule** (regex/fingerprint) to auto-identify the source.
- The framework hot-reloads the configuration every 60 seconds; no restart needed.
- A REST API endpoint (`/v1/sources`) lists all active sources and their status.

### f) Unified visibility across enterprise environments
- All normalized events are emitted with a common schema, allowing:
  - Single dashboard (via Grafana/Elastic) across network, cloud, containers.
  - Consistent field names for correlation (e.g., `source_ip` always means the same).
- The output sinks can be configured per environment; the same pipeline runs everywhere.

### g) Efficient SIEM and Data Lake integration
- **SIEM**: Stream normalized events to Elasticsearch via bulk API; or to Kafka for QRadar/Splunk.
- **Data Lake**: Write Parquet files partitioned by `date` and `source_type` to a local/remote filesystem.
- Integration requires only connection details in config; no custom code.

### h) AI/ML-ready security and operational analytics
- Normalized events are output in **Parquet** and **JSON** formats, directly consumable by pandas, Spark, or ML pipelines.
- The schema includes:
  - `normalized_features` (map) for precomputed ML features (e.g., entropy, frequency).
  - `ml_tags` field for enrichment (e.g., threat intelligence scores).
- A lightweight ML inference module (ONNX runtime) can be optionally enabled to add anomaly scores inline.

### i) Reduced parser development effort
- Instead of writing full parsers, developers can:
  - Use predefined **template parsers** (JSON, CSV, regex) with a config.
  - Define new formats via a **DSL in YAML** (e.g., `fields: [timestamp, src_ip, dst_ip]`).
- The framework includes a **parser testing CLI** that validates new rules against sample logs.

### j) The solution shall be deployable in an air-gapped network
- Single binary with **zero external dependencies** (no package manager, no internet).
- All docker images can be built offline; or deploy as standalone binary.
- Configuration can be entirely local; no telemetry or external calls.
- Optional internal mirrors for threat intelligence feeds.

### k) Solution may be packaged in a container for making it platform independent
- Official Dockerfile provided, building from scratch or Alpine.
- Supports both `amd64` and `arm64`.
- Also provide a `.tar.gz` with the binary + configs for non-container environments.

---

## 4. Handling "Billions of Events per Day" Scale

- **Ingestion**: gRPC with keep-alive and batching; uses a **ring buffer** for backpressure.
- **Parsing**: Goroutine pool per source; work is sharded by `source_id` to avoid contention.
- **Normalization**: In-memory cache for field mappings; compiled CEL programs cached.
- **Storage**: Sequential writes to raw blob store; metadata index uses LSM-tree (RocksDB) for high write throughput.
- **Output**: Batched writes to sinks (e.g., Elasticsearch bulk size 10k).
- **Benchmarking**: Internal metrics show sustained 50k events/sec per core; scalable horizontally by running multiple instances with a load balancer.
- **Partitioning**: Events are partitioned by `source_type` and `hour` for data lake outputs.

---

## 5. Air-Gapped Deployment

- **No external registries**: All dependencies vendored in `vendor/` or embedded via `go:embed`.
- **Offline installer**: Provides a single tarball with binary, configs, and sample parsers.
- **No telemetry**: All monitoring is local (Prometheus endpoint) with no external push.
- **Internal security**: Certificate generation (mTLS) is built-in; no external CA required.
- **Update mechanism**: Manual binary replacement or config update; no automated pull.

---

## 6. Hackathon Prototype vs. Production Essentials

### ✅ What I would include in the prototype (essential):
- End-to-end pipeline for 5 sample sources (Syslog, JSON, CSV, CEF, XML).
- Working field mapping for at least 20 common fields.
- Raw storage + traceability via hash.
- Basic output to Elasticsearch and local Parquet.
- Configuration hot-reload.
- Docker container and air-gap tarball.
- Simple REST API for health and source status.

### ❌ What I would explicitly leave out (out of scope for prototype):
- ML inference engine (just stub with placeholder fields).
- Distributed clustering / consensus (use single-node or manual sharding).
- Web UI / Dashboard (provide only API and config; use Grafana separately).
- Multi-tenant authentication/authorization.
- Advanced error recovery (e.g., exactly-once semantics across sink failures).
- Dynamic plugin compilation (provide prebuilt parsers; compilation requires dev tools).
- Full threat intelligence feed integration (use static local file).
- Complex query API (just push to sinks).

---

## 7. Summary of Single-Application Cohesion

All components (ingestion, parsing, normalization, storage, routing, monitoring) are compiled into **one binary** that runs as a single process. Configuration drives everything; no separate services, no service discovery, no external orchestration. This ensures:

- **Simplified deployment** in air-gapped environments.
- **Deterministic behavior** (no network dependency between internal components).
- **Easier debugging** (single log file, single process).
- **Predictable performance** (no inter-service overhead).

The design prioritizes **operational simplicity** and **modularity through configuration**, not through microservices, making it ideal for government/enterprise environments that value control and reliability over theoretical scalability.

---

**End of Design Document**

---

## Comparison Notes
After analyzing the four independent model responses, several clear points of agreement, key differentiators, and potential blind spots emerge. The models demonstrate a strong consensus on the core non-negotiable principles but diverge significantly on the execution and technology stack, primarily based on their interpretation of "one unified application."

1. Where did models agree on the core architecture (single app vs. split)?
Strong, Unanimous Agreement on "Modular Monolith":

Absolute Consensus: All four models, without exception, rejected a microservices architecture. They all interpret "single, unified application" as a single deployable unit (a JAR, static binary, or single process).

Agreed Core Principles:

Preserve-First Pipeline: Every model places "raw preservation" as the very first step in the pipeline, before any parsing, normalizing, or analysis. This is the most critical and universally agreed-upon architectural principle.
Configuration-Driven Parsing: All models propose a generic, rule-based parser engine (YAML, HCL, etc.) to avoid writing custom code for every vendor, which is the key to "reduced parser effort."
Separation of Storage: Each design logically separates storage: a metadata/control store, a raw immutable data store, and an analytical store.
Traceability: Every model uses a unique event_id and a hash of the raw payload within the normalized event to provide a direct link back to the original source.
No LLM in Hot Path: All models agree that using a Large Language Model (LLM) to parse every event is a non-starter for the required "billions of events per day" scale. Any AI/ML assistance is relegated to the onboarding process or used for downstream anomaly detection.
2. Which model proposed a genuinely different storage strategy?
Model 3 (Rust/Arrow/Flink) proposes the most innovative and distinct storage strategy.

Model 1 & 2 & 4's Strategy: Their approach is a more traditional, "pluggable component" model. They use a dedicated, well-established system for each layer: MinIO (Object Store) for raw, PostgreSQL (RDBMS) for metadata, and ClickHouse (Columnar DB) for analytics. This is a robust, battle-tested pattern using external infrastructure components.

Model 3's Genuinely Different Strategy: Model 3 proposes a Zero-Copy, Columnar-Native pipeline. It ingests data, processes it with Apache Arrow in-memory, and writes directly to Apache Parquet (Data Lake) for the "cold" path, while simultaneously feeding a local ClickHouse for "hot" queries.

Key Differentiator: The raw data is not necessarily stored as isolated files in MinIO. It is preserved by being written as immutable Parquet files, which are themselves the "raw ledger." The traceability hash then links the normalized view to these Parquet blocks.

Advantage: This is a much more integrated strategy, minimizing the serialization/deserialization overhead between layers (JSON -> Struct -> Parquet -> ClickHouse). It uses the data lake format as the primary source of truth, rather than a secondary or tertiary destination.

Model 4's Nuance: Model 4 is also distinct in using an embedded RocksDB for metadata and the local filesystem for raw storage, avoiding the need for a separate PostgreSQL or MinIO service. However, it's less "different" than Model 3's Arrow/Parquet-centric approach.

3. Did any model suggest an AI/ML approach we hadn't considered?
Yes. Model 3 and Model 4 propose interesting techniques that go beyond the standard "LLM helps write YAML" idea.

Model 1 (Claude): Proposes a multi-layered matching approach (exact alias -> statistical -> typo -> semantic embedding) to assist in field mapping. This suggests a more sophisticated local ML model for the onboarding UI than just a simple prompt-to-YAML converter.

Model 3 (Rust): Suggests the use of a WASM (WebAssembly) plugin interface for parsing. While not strictly AI, this is a novel way to allow for "smart" plugins to be loaded securely and dynamically in an air-gapped environment, potentially leveraging an ML model compiled to WASM for specific parsing tasks.

Model 4 (Go): Mentions using a "ML-assisted fingerprinting" for format detection and embedding an ONNX runtime to add anomaly scores inline to the normalized event output. This is the most direct incorporation of local, real-time ML inference in the processing pipeline (as opposed to just using it for onboarding).

4. Did any model miss or hand-wave the air-gap or billions-of-events constraints?
Model 4 (Go) and Model 1 (Claude) are the most problematic, though for different reasons.

Model 4 (Go): This model hand-waves the "billions of events/day" the most. It states it can handle 50k events/sec per core, but a single core system would be overwhelmed by even 1 billion events/day (~11.5k eps), and relying on "multiple instances" is acknowledged but not deeply explored. Its use of the local filesystem and embedded RocksDB for high-throughput is a potential bottleneck that is not well-addressed. The air-gap is handled well.

Model 1 (Claude): This model is the least grounded in the "billions" constraint. It proposes a Spring Boot monolith with an embedded SQLite database. While this is a perfectly valid "single app" approach, SQLite has known write contention issues under high concurrency. Even with ClickHouse for analytics, the metadata and control-plane stored in SQLite might become a bottleneck during high-volume onboarding or configuration changes. Its architecture feels more like a prototype than a design for the specified scale.

Models 2 and 3 are the most explicit and convincing in their scaling arguments. Model 2 directly addresses partition-based scaling of Kafka, while Model 3 proposes advanced techniques like lock-free concurrency, zero-copy, and vectorized processing (SIMD) that are compelling for high throughput.

5. Anything here worth actually adopting into our real implementation?
Yes, absolutely. Several specific elements from these model responses are valuable to incorporate.

Adopt "Preserve-First" as a Mantra: The absolute consensus on this point validates it as the single most critical architectural principle. The strict order of RECEIVE -> STORE RAW -> PARSE must be enforced.

Adopt a Configuration-Driven Generic Parser: All models support this. The exact format (YAML, HCL, etc.) is secondary, but building our own "micro DSL" for log parsing is the only practical way to achieve plug-and-play onboarding without writing Java code.

Prioritize Data Lake + Columnar Store: Using Parquet/Iceberg for the data lake and ClickHouse for analytics is a consistent recommendation from Models 2 and 3. This aligns with industry best practices and we should adopt this as a core data strategy.

Reconsider the Stack for the Real Implementation: The model responses present three very different tech stacks for consideration:

Model 2 (Java/Spring): This is the most "enterprise-friendly" and matches our codebase. It is safe, well-understood, and its modular-monolith architecture with a Kafka backbone is robust and scalable.

Model 3 (Rust): This is the most performance-optimized but introduces a steep learning curve and a smaller ecosystem. Its use of Arrow and Parquet is very compelling for a data-intensive pipeline.

Model 4 (Go): This is the best "hackathon" choice. It offers a pragmatic balance of simplicity, performance, and a single deployable binary. For a true production system, the networking and reliability might need more work, but the deployment simplicity is valuable for an air-gapped environment.

Investigate WASM for Plugin Architecture: Model 3's suggestion of using WASM for parser plugins is highly innovative. It would allow us to develop a robust, secure, and dynamic way for users to onboard new, complex sources without needing to learn the entire framework.

Incorporate a Hybrid AI Onboarding Workflow:

Use the deterministic, configuration-driven parser engine for the hot path (like all models suggest).

Adopt Model 1's proposed multi-layered matching (alias, statistical, typo, semantic) for the onboarding UI, integrating a local ONNX embedding model (as suggested by Model 4) to propose field mappings for new sources. This is a very practical and deliverable use of AI.

Re-evaluate the "Blockchain" Requirement: The Blockchain element is poorly understood. Model 2 is the only one to provide a reasoned analysis, correctly suggesting that a Merkle-tree-based tamper-evidence system is a more practical and useful application of the "blockchain & cybersecurity" theme than deploying a full distributed ledger. This is the direction we should pursue.

