# Universal Log Framework (ULPF) — Architecture & Technical Design

## 1. Document Purpose

This document provides a comprehensive technical architecture reference for the Universal Log Framework (ULPF). While the [README.md](file:///home/abhinav/Desktop/ULPF/README.md) serves as an introductory overview and quick-start guide, this document details the system design, control-plane and data-plane separation, entity-relationship data models, canonical event translation, mapping lifecycle state transitions, and explicit architectural boundaries governing the SIH prototype.

---

## 2. Control Plane vs. Data Plane

ULPF strictly segregates management operations from real-time event ingestion to ensure high availability, predictability, and performance.

```text
+-----------------------------------------------------------------------------------+
|                                  CONTROL PLANE                                    |
|                                                                                   |
|  [ Web UI / Admin ] ---> [ Control API ] ---> [ SQLite DB ]                       |
|                                |                                                  |
|                        [ AI Mapping Engine ]                                      |
+-----------------------------------------------------------------------------------+
                                         |
                       Active Mapping & Credentials State
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                                   DATA PLANE                                      |
|                                                                                   |
|  [ Vendor Log Source ] ---> POST /v1/events ---> [ Raw Event Store ]             |
|                                                       |                           |
|                                              (Normalization Pipeline)             |
|                                                       |                           |
|                                                       v                           |
|                                            [ ClickHouse Event DB ]                |
+-----------------------------------------------------------------------------------+
```

### Control Plane
- **Responsibilities**: User authentication, vendor registration, source provisioning, AI-assisted schema discovery, mandatory human review of proposed mappings, credential/API key lifecycle management, and user notifications.
- **Storage Layer**: Embedded **SQLite** database (`data/control-plane.db`). SQLite provides zero-configuration, lightweight transactional storage for low-frequency control metadata across 7 tables (`users`, `vendors`, `sources`, `credentials`, `mapping_versions`, `onboarding_requests`, `notifications`).

### Data Plane
- **Responsibilities**: High-throughput ingestion of vendor logs, immediate raw payload preservation, credential verification, active mapping lookup, event field normalization, and persistent insertion into analytical storage.
- **Storage Layer**: **ClickHouse** analytical column-store database. ClickHouse handles high-volume log writes, column compression, and fast aggregate querying for visualization dashboards.

---

## 3. Onboarding & Mapping Workflow

The control-plane onboarding process registers a vendor, processes sample log structures using AI, and mandates administrator approval before issuing ingestion credentials.

```text
Vendor                  Control API               SQLite DB             AI Mapping Engine           Admin (Web UI)
  |                          |                        |                        |                          |
  |--- 1. Register Account ->|                        |                        |                          |
  |                          |--- 2. Insert User ---->|                        |                          |
  |                          |                        |                        |                          |
  |--- 3. Upload Sample ---->|                        |                        |                          |
  |                          |--- 4. Save Request --->| (status: SUBMITTED)    |                          |
  |                          |                        |                        |                          |
  |                          |--- 5. Analyze Sample -------------------------->|                          |
  |                          |                        |                        |-- 6. Mapping Proposal ->|
  |                          |<-- 7. Save Candidate --| (status: AI_ANALYSIS)  |                          |
  |                          |                        |                        |                          |
  |                          |--- 8. Queue for Review---------------------------------------------------->|
  |                          |                        | (status: HUMAN_REVIEW) |                          |--- 9. Inspect Proposal
  |                          |                        |                        |                          |--- 10. Approve/Edit
  |                          |<-- 11. Submit Decision ----------------------------------------------------|
  |                          |                        |                        |                          |
  |                          |--- 12. Save Mapping -->| (status: ACTIVE)       |                          |
  |                          |--- 13. Issue API Key ->| (credentials)          |                          |
  |<-- 14. API Key & Status -|                        |                        |                          |
```

---

## 4. Ingestion & Processing Flow

The data-plane runtime path processes events sent to the single `POST /v1/events` endpoint. Raw preservation occurs prior to transformation to guarantee zero data loss.

```text
[ Vendor Payload ] 
        |
        v
[ POST /v1/events ]
        |
        +---> [ 1. Lossless Raw Event Store ] (Preserves original unparsed JSON/syslog payload)
        |
        +---> [ 2. Authenticate Credential ] (Hashes API Key & looks up source in credentials table)
        |
        +---> [ 3. Resolve Active Mapping ] (Fetches active mapping version for source_id)
        |
        +---> [ 4. Normalize Event ] (Transforms vendor fields -> Canonical Event Model)
        |
        v
[ ClickHouse Column Store ] (Stored in partition by date/event_class for fast querying)
```

---

## 5. Control-Plane Data Model (SQLite)

The control-plane database schema consists of 7 relational tables defined in [core-engine/sqlite-init/schema.sql](file:///home/abhinav/Desktop/ULPF/core-engine/sqlite-init/schema.sql):

```text
   +-------------------+
   |       users       |
   +-------------------+
   | user_id (PK)      |
   | username          |
   | password_hash     |
   | role              |
   +-------------------+
             | 1
             |
             | 1 (UNIQUE)
             v
   +-------------------+             +-----------------------+
   |      vendors      |             |  onboarding_requests  |
   +-------------------+             +-----------------------+
   | vendor_id (PK)    |             | request_id (PK)       |
   | owner_user_id(FK) |<------------| user_id (FK)          |
   | vendor_name       |             | source_id (FK, opt)   |
   | status            |             | request_type          |
   +-------------------+             | sample_metadata       |
             | 1                     | status                |
             |                       +-----------------------+
             | N
             v                       +-----------------------+
   +-------------------+             |     notifications     |
   |      sources      |             +-----------------------+
   +-------------------+             | notification_id (PK)  |
   | source_id (PK)    |------------>| user_id (FK)          |
   | vendor_id (FK)    |             | title                 |
   | source_name       |             | message               |
   | source_type       |             | read                  |
   | status            |             +-----------------------+
   +-------------------+
     | 1             | 1
     |               |
     | N             | N
     v               v
+------------------+ +------------------+
|   credentials    | | mapping_versions |
+------------------+ +------------------+
| credential_id(PK)| | mapping_id (PK)  |
| source_id (FK)   | | source_id (FK)   |
| key_hash (Idx)   | | version          |
| status           | | mapping_json     |
+------------------+ | status (Idx)     |
                     +------------------+
```

### Table Specifications
1. **`users`**: System accounts (`ADMIN`, `VENDOR`, `USER`).
2. **`vendors`**: Vendor entities linked 1:1 to an owner user via `owner_user_id`.
3. **`sources`**: Log sources associated with a vendor (e.g., `FIREWALL`, `WEB_APP`, `DATABASE`, `SENSOR`).
4. **`credentials`**: Hashes of API keys generated for log sources. Indexed on `key_hash`.
5. **`mapping_versions`**: Versioned JSON schema mapping configurations. Constrained by `UNIQUE(source_id, version)` and indexed on `(source_id, status)`.
6. **`onboarding_requests`**: Tracking table for vendor onboarding, source registration, or schema update requests (`SUBMITTED`, `AI_ANALYSIS`, `HUMAN_REVIEW`, `APPROVED`, `REJECTED`).
7. **`notifications`**: System notifications dispatched to users regarding request updates.

---

## 6. Canonical Event Model

Different log vendors format identical telemetry fields under different key names. ULPF maps vendor-specific JSON fields to standardized **canonical fields**.

### Field Mapping Example: `source_ip`
- **Palo Alto Firewall Log**: `"src": "192.168.1.50"` $\rightarrow$ `canonical.source_ip = "192.168.1.50"`
- **Nginx Web Server Log**: `"remote_addr": "192.168.1.50"` $\rightarrow$ `canonical.source_ip = "192.168.1.50"`
- **AWS CloudTrail Log**: `"sourceIPAddress": "192.168.1.50"` $\rightarrow$ `canonical.source_ip = "192.168.1.50"`

The active `mapping_json` for a source specifies target JSON path extractions, data type conversions, and canonical field destinations.

---

## 7. Mapping Version Lifecycle

Schema evolution for log sources is managed through an explicit state machine:

```text
[ New Upload / Schema Change ]
              |
              v
       ( CANDIDATE )  <--- AI Mapping Engine generates proposal
              |
              v
     ( HUMAN_REVIEW ) <--- Admin inspects/edits proposal in Web UI
              |
      +-------+-------+
      |               |
      v               v
  ( APPROVED )    ( REJECTED )
      |
      v
   ( ACTIVE )         <--- Replaces previous active version for source
      |
      v
  ( RETIRED )        <--- Deprecated when a newer version is activated
```

- **`CANDIDATE`**: Generated by AI mapping engine upon analyzing log samples.
- **`HUMAN_REVIEW`**: Pending administrator verification or modification.
- **`ACTIVE`**: The currently operational mapping used by the data plane for event normalization.
- **`RETIRED`**: Superceded mapping retained for historical auditing.

---

## 8. Technology Boundaries & Constraints

To maintain a lean, robust prototype, the following architecture decisions were explicitly made:

- **No Distributed Message Queues (e.g., Kafka / RabbitMQ)**: Direct HTTP ingestion into raw storage and ClickHouse satisfies prototype throughput requirements without operating heavy cluster infrastructure.
- **No Container Orchestrators (e.g., Kubernetes)**: Services run natively or via local Podman containers (`compose.yaml`).
- **No SSO / External Auth Providers**: Authentication uses lightweight local SQLite user table with hashed credentials.
- **Application-Layer JSON Handling**: JSON payloads and mapping definitions are stored as `TEXT` in SQLite and parsed in application logic (Java / Python) rather than relying on vendor-specific DB JSON extensions.
