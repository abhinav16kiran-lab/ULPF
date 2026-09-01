# ULPF — File Guide

This document explains what each file and folder in the ULPF repository is responsible for.

The guide describes the current repository structure. Empty folders are planned package locations; their implementation will be added as the project progresses.

## 1. Repository Root

```text
ULPF/
├── core-engine/
├── dev-tools/
├── docs/
├── frontend/
├── infra/
└── README.md
```

| File / Folder | Purpose |
|---|---|
| `README.md` | Main project entry point. Contains the project overview, setup/use information, and important project references. |
| `core-engine/` | Main Java + Spring Boot backend containing the control plane, data plane, mapping, analytics, database initialization, and application configuration. |
| `frontend/` | React frontend that communicates with ULPF through the backend APIs. |
| `infra/` | Infrastructure configuration, including ClickHouse initialization and container configuration. |
| `dev-tools/` | Development and demonstration utilities. These are not production application services. |
| `docs/` | Project documentation, diagrams, setup instructions, technical design, API specification, database schema, and prototype checklist. |

## 2. `core-engine/`

The main ULPF backend application.

```text
core-engine/
├── sqlite-init/
├── src/
├── Containerfile
└── pom.xml
```

| File / Folder | Purpose |
|---|---|
| `Containerfile` | Defines how the Spring Boot backend is packaged into a container image. |
| `pom.xml` | Maven project configuration, including dependencies and build configuration. |
| `sqlite-init/schema.sql` | SQLite control-plane schema, including tables, keys, constraints, and indexes. |
| `src/` | Java source code and Spring Boot configuration. |

### `core-engine/sqlite-init/schema.sql`

Contains the ULPF-owned SQLite control-plane schema:

```text
users
vendors
sources
credentials
mapping_versions
mapping_embeddings
onboarding_requests
notifications
```

SQLite stores control-plane information such as users, vendors, sources, credentials, mapping versions, mapping embeddings, onboarding requests, and notifications.

## 3. `core-engine/src/main/java/com/ulpf/`

The main Java package for ULPF.

```text
com/ulpf/
├── analytics/
│   ├── controller/
│   ├── model/
│   ├── repository/
│   └── service/
├── common/
├── controlplane/
│   ├── controller/
│   ├── model/
│   │   ├── Role.java
│   │   └── User.java
│   ├── repository/
│   │   └── UserRepository.java
│   └── service/
├── dataplane/
│   ├── controller/
│   ├── model/
│   └── service/
├── mapping/
└── UlpfApplication.java
```

### `UlpfApplication.java`

Main Spring Boot application entry point.

### `common/`

Shared backend functionality used by more than one module, such as common utilities, exceptions, validation, or other cross-cutting functionality as implementation requires.

### `controlplane/`

Control-plane functionality for users, vendors, sources, credentials, onboarding, mapping versions, mapping embeddings, notifications, and related metadata/configuration.

- `controller/` — HTTP/API controllers for control-plane operations.
- `model/` — Control-plane Java models.
- `Role.java` — Current user roles: `ADMIN`, `VENDOR`, and `USER`.
- `User.java` — Java representation of a system user.
- `repository/` — Database-access layer for control-plane data.
- `UserRepository.java` — Current user repository.
- `service/` — Control-plane business logic.

### `dataplane/`

Runtime event ingestion and processing.

The core flow is:

```text
POST /v1/events
        ↓
Spring Boot
        ↓
Generate event_id
        ↓
Preserve raw event
        ↓
ClickHouse raw_events
        ↓
Parse / map / normalize
        ↓
Canonical event
        ↓
ClickHouse normalized event table
```

- `controller/` — Runtime data-plane HTTP endpoints, including event ingestion.
- `model/` — Data structures for incoming, raw, and normalized events.
- `service/` — Event processing, mapping-version resolution, normalization, lineage, sensor handling, and persistence logic as implemented.

### `mapping/`

Mapping-engine functionality inside the Spring Boot application. It is not a separate microservice.

The current conceptual flow is:

```text
Incoming field
      ↓
Normalize name
      ↓
Tokenize
      ↓
Alias / custom dictionary lookup
      ↓
TF-IDF similarity
      ↓
Candidate ranking
      ↓
Confidence
      ↓
Threshold
      ↓
Mapping / unknown-field proposal
      ↓
Optional local MiniLM fallback
      ↓
Human approval
```

The mapping engine produces proposals. Human approval remains compulsory for onboarding/schema proposals, and AI does not directly activate production schema changes.

### `analytics/`

Analytics functionality inside the same Spring Boot application rather than a separate analytics microservice.

The current flow is:

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

- `controller/` — Analytics HTTP endpoints such as `GET /v1/analytics`.
- `model/` — Analytics request/response and result models as implementation develops.
- `repository/` — ClickHouse access for analytics queries.
- `service/` — Analytics validation, authorization-related checks, query handling, and result processing.

## 4. `core-engine/src/main/resources/`

```text
resources/
├── application-dev.yaml
├── application-prod.yaml
└── application.yaml
```

| File | Purpose |
|---|---|
| `application.yaml` | Base Spring Boot configuration. |
| `application-dev.yaml` | Development-specific configuration. |
| `application-prod.yaml` | Production-specific configuration. |

Sensitive credentials should not be hard-coded into configuration files.

## 5. `frontend/`

```text
frontend/
├── Containerfile
├── package.json
└── vite.config.js
```

React frontend.

- `Containerfile` — Builds the frontend container image.
- `package.json` — Frontend dependencies, scripts, and metadata.
- `vite.config.js` — Vite configuration.

As implementation progresses, source code can be added under `frontend/src/` and organized into areas such as onboarding, admin, dashboard, components, and API services.

The frontend should communicate with Spring Boot rather than directly accessing SQLite or ClickHouse.

## 6. `infra/`

```text
infra/
├── clickhouse-init/
│   └── 01_raw_events.sql
└── compose.yaml
```

### `compose.yaml`

Defines the containerized infrastructure used by the project.

Persistent volumes are used for database persistence so recreating a container does not automatically destroy stored database data.

### `clickhouse-init/01_raw_events.sql`

Initializes the ULPF-owned ClickHouse structure for a fresh ClickHouse data directory:

```text
ClickHouse
├── ulpf_raw
│   └── raw_events
└── ulpf_events
    └── initially empty
```

`ulpf_raw.raw_events` is the predefined raw-event table. Runtime canonical event tables are created or changed after vendor onboarding and required human approval; they are not predicted and pre-created in this initialization directory.

## 7. `dev-tools/`

```text
dev-tools/
├── analytics_demo.py
├── ml_demo.py
└── seed_control_plane.py
```

These are development/demo utilities, not production ULPF services.

| File | Purpose |
|---|---|
| `analytics_demo.py` | Experiment with or demonstrate analytics/data-analysis operations against ClickHouse. |
| `ml_demo.py` | Experiment with or demonstrate machine-learning/data-science functionality. |
| `seed_control_plane.py` | Insert starter/test control-plane data for development. |

Python therefore remains available for development and demonstrations without making Python a required production analytics service.

## 8. `docs/`

Contains project documentation and diagrams.

```text
docs/
├── Images/
├── Your first ??/
├── ANYTHING_I_AM_MISSING.md
├── API_SPECIFICATION.md
├── ARCHITECTURE.md
├── DATABASE_SCHEMA.md
├── EVERYTHING_THAT_NEEDS_TO_BE_DONE_BEFORE_PROTOTYPE_SUB.md
└── PROTOTYPE_TECHNICAL_DESIGN.md
```

### `docs/Images/`

| File | Purpose |
|---|---|
| `Control pannel.png` | Control-plane/control-panel flow visualization. |
| `dataIngestionAndProcessing.png` | Event ingestion and processing flow. |
| `mapping engine fi.png` | Mapping-engine flow. |
| `Onboarding and logging.png` | Onboarding and logging workflow. |
| `versionLife.png` | Mapping/version lifecycle visualization. |

### `docs/Your first ??/`

Contains:

```text
FILE_GUIDE.md
TEAMMATE_ONBOARDING.md
ULPF_Dev_Environment_Setup.md
```

- `FILE_GUIDE.md` — This file; explains the repository structure.
- `TEAMMATE_ONBOARDING.md` — Information for new team members joining the project.
- `ULPF_Dev_Environment_Setup.md` — Development environment setup instructions.

The folder name `Your first ??` is retained because it is the current repository structure. It can be renamed later if the team chooses a clearer name.

### `docs/API_SPECIFICATION.md`

Source of truth for ULPF API contracts. Current core endpoints include:

```text
POST /v1/events
POST /v1/onboard
POST /v1/login
GET  /v1/notifications
GET  /v1/analytics
```

### `docs/ARCHITECTURE.md`

Detailed architecture and component responsibilities, including control plane, data plane, mapping, schema management, lineage, analytics, persistence, and deployment.

### `docs/DATABASE_SCHEMA.md`

Documents the SQLite control-plane schema and ClickHouse data-plane schema, including the predefined raw-events structure and runtime-created canonical event tables.

### `docs/EVERYTHING_THAT_NEEDS_TO_BE_DONE_BEFORE_PROTOTYPE_SUB.md`

Working checklist of implementation, integration, testing, deployment, documentation, and demo work required before prototype submission.

### `docs/PROTOTYPE_TECHNICAL_DESIGN.md`

Broader technical design baseline for the SIH prototype.

### `docs/ANYTHING_I_AM_MISSING.md`

Living list of unresolved gaps and open decisions. Items should be explicitly marked rather than silently assumed to be decided.

## 9. High-Level Backend Architecture

The backend is one Spring Boot application containing multiple logical modules:

```text
com/ulpf/
│
├── analytics/
├── common/
├── controlplane/
├── dataplane/
├── mapping/
└── UlpfApplication.java
```

The high-level runtime architecture is:

```text
                         React
                           │
                           ▼
                    ┌─────────────┐
                    │ Spring Boot │
                    │             │
                    │ Control     │
                    │ Data        │
                    │ Mapping     │
                    │ Analytics   │
                    └──────┬──────┘
                           │
                 ┌─────────┴─────────┐
                 ▼                   ▼
              SQLite             ClickHouse
           Control Plane          Data Plane
```

The project intentionally keeps these responsibilities inside one backend application rather than creating a separate microservice for every function.

## 10. Production vs Development

Production application:

```text
React + Spring Boot + SQLite + ClickHouse
```

Development/demo tooling:

```text
dev-tools/
├── analytics_demo.py
└── ml_demo.py
```

Python demo tooling does not imply that Python must be deployed in the production ULPF runtime.

## 11. Important Rule for This Guide

This document should describe the repository **as it actually exists**.

When new files or packages are created, update this guide so teammates can understand their purpose. Do not document classes, files, services, or subpackages that have not actually been created yet.
