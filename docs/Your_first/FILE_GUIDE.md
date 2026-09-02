# ULPF — File Guide

This document explains what each file and folder in the ULPF repository is responsible for.

The guide describes the current repository structure. Empty folders are planned package locations; their implementation will be added as the project progresses.

## 1. Repository Root

```text
ULPF/
├── compose.yaml
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
| `compose.yaml` | Master Docker/Podman Compose orchestration file. Configures `clickhouse` and `core-engine` services, bridge network `ulpf-net`, and persistent volumes. |
| `core-engine/` | Main Java + Spring Boot backend containing the control plane, data plane, mapping, analytics, and application configuration. |
| `frontend/` | React frontend that communicates with ULPF through backend APIs. |
| `infra/` | Infrastructure configuration, including ClickHouse container setup, init scripts, and performance settings. |
| `dev-tools/` | Development and demonstration utilities. These are not production application services. |
| `docs/` | Project documentation, diagrams, setup instructions, technical design, API specification, database schema, and prototype checklist. |

---

## 2. `core-engine/`

The main ULPF backend application.

```text
core-engine/
├── src/
├── .dockerignore
├── Containerfile
└── pom.xml
```

| File / Folder | Purpose |
|---|---|
| `Containerfile` | Multi-stage Docker/Podman build file (Maven builder + OpenJDK 21 JRE runtime) for packaging the backend into a container image. |
| `.dockerignore` | Build context rules excluding target binaries and local SQLite database files from container builds. |
| `pom.xml` | Maven project configuration, including dependencies and build configuration. |
| `src/` | Java source code, Spring Boot configuration, and SQLite schema DDL (`src/main/resources/sqlite/schema.sql`). |

### `core-engine/src/main/resources/sqlite/schema.sql`

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

---

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
│   ├── JwtFilter.java
│   ├── JwtUtil.java
│   ├── SpringSecurity.java
│   └── UlpfPrincipal.java
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
Security and authentication infrastructure using Spring Security & JWT:
- `JwtUtil.java` — JWT generation, parsing, claim extraction, and validation.
- `JwtFilter.java` — HTTP filter intercepting `Bearer` tokens and building Spring Security context.
- `SpringSecurity.java` — Security filter chain configuration, stateless session policies, and endpoint authorization rules.
- `UlpfPrincipal.java` — Java record data holder representing the authenticated user principal (`id`, `username`).

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

- `controller/` — Runtime data-plane HTTP endpoints, including event ingestion.
- `model/` — Data structures for incoming, raw, and normalized events.
- `service/` — Event processing, mapping-version resolution, normalization, lineage, sensor handling, and persistence logic as implemented.

### `mapping/`
Mapping-engine functionality inside the Spring Boot application. It is not a separate microservice.

### `analytics/`
Analytics functionality inside the Spring Boot application.

---

## 4. `core-engine/src/main/resources/`

```text
resources/
├── sqlite/
│   └── schema.sql
├── application-dev.yaml
├── application-prod.yaml
└── application.yaml
```

| File | Purpose |
|---|---|
| `sqlite/schema.sql` | Consolidated SQLite schema script auto-initialized by Spring Boot on startup (`spring.sql.init.schema-locations=classpath:sqlite/schema.sql`). |
| `application.yaml` | Base Spring Boot configuration (datasource, port, auto-initialization settings). |
| `application-dev.yaml` | Development-specific logging options. |
| `application-prod.yaml` | Production-specific configuration. |

---

## 5. `frontend/`

```text
frontend/
├── Containerfile
├── package.json
└── vite.config.js
```

React frontend.

---

## 6. `infra/`

```text
infra/
├── clickhouse-config/
│   └── users.d/
│       └── async_inserts.xml
├── clickhouse-init/
│   └── 01_raw_events.sql
└── Containerfile
```

### `Containerfile`
Standalone Docker/Podman build file for packaging the ClickHouse container image (derived from `docker.io/clickhouse/clickhouse-server:26.3`) bundled with initialization scripts and user configuration.

### `clickhouse-config/users.d/async_inserts.xml`
Custom ClickHouse user profile settings enabling asynchronous inserts and memory batching (`async_insert=1`, `wait_for_async_insert=1`, `async_insert_busy_timeout_ms=200`).

### `clickhouse-init/01_raw_events.sql`
Initializes ULPF ClickHouse databases (`ulpf_raw` and `ulpf_events`) and table `ulpf_raw.raw_events` for a fresh ClickHouse installation.

---

## 7. `dev-tools/`

```text
dev-tools/
├── analytics_demo.py
├── ml_demo.py
└── seed_control_plane.py
```

Development/demo utilities.

---

## 8. `docs/`

Contains project documentation and diagrams.

```text
docs/
├── Images/
├── Your_first/
├── ANYTHING_I_AM_MISSING.md
├── API_SPECIFICATION.md
├── ARCHITECTURE.md
├── DATABASE_SCHEMA.md
├── EVERYTHING_THAT_NEEDS_TO_BE_DONE_BEFORE_PROTOTYPE_SUB.md
└── PROTOTYPE_TECHNICAL_DESIGN.md
```

- `Your_first/FILE_GUIDE.md` — This file; explains the repository structure.
- `Your_first/TEAMMATE_ONBOARDING.md` — Information for new team members joining the project.
- `Your_first/ULPF_Dev_Environment_Setup.md` — Development environment setup instructions.

---

## 9. High-Level Backend Architecture

The backend is one Spring Boot application containing multiple logical modules:

```text
com/ulpf/
│
├── analytics/
├── common/
│   ├── JwtFilter.java
│   ├── JwtUtil.java
│   ├── SpringSecurity.java
│   └── UlpfPrincipal.java
├── controlplane/
├── dataplane/
├── mapping/
└── UlpfApplication.java
```

Runtime architecture:

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

---

## 10. Important Rule for This Guide

This document should describe the repository **as it actually exists**.
When new files or packages are created, update this guide so teammates can understand their purpose.
