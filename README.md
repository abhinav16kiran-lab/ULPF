# Universal Log Framework (ULPF)

Universal Log Framework (ULPF) is a plug-and-play log ingestion platform designed to eliminate manual schema integration friction for multi-vendor environments. Vendors register and submit sample log payloads, after which an AI mapping engine analyzes the data to propose semantic mappings into a canonical log schema. A human administrator reviews, edits, and approves the proposal before an ingestion API key is granted. Once onboarded, vendors send logs through a single runtime endpoint where incoming raw logs are preserved losslessly, normalized against the active mapping version, and stored in high-performance analytical storage.

For detailed system design and architectural specifications, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/PROTOTYPE_TECHNICAL_DESIGN.md](docs/PROTOTYPE_TECHNICAL_DESIGN.md).

---

## What It Does

The system operates across two primary workflows: **Control-Plane Onboarding** and **Data-Plane Ingestion**.

### 1. Vendor Onboarding Workflow
1. **Registration**: Vendor registers an account (`username`, `password`, `vendor_name`) via the control plane UI.
2. **Sample Upload**: Vendor submits sample log payloads and optional documentation for a new log source.
3. **Onboarding Request**: System logs an `onboarding_request` in SQLite marked as `SUBMITTED`.
4. **AI Analysis**: The AI mapping engine analyzes the payload structure and generates a candidate mapping proposal with confidence scores (`AI_ANALYSIS`).
5. **Human Review**: Request moves to `HUMAN_REVIEW`. An administrator inspects the proposed mapping via the admin dashboard, makes any necessary adjustments, and approves or rejects it.
6. **Activation & Key Generation**: Upon approval (`APPROVED`), the mapping is saved to `mapping_versions` as `ACTIVE`, and a unique API key credential is created in `credentials`.
7. **Vendor Notification**: Vendor receives a notification containing their API key and status update.

### 2. Runtime Log Ingestion Workflow
1. **Event Receipt**: Vendor sends log payloads using their API key via `POST /v1/events`.
2. **Raw Preservation**: System immediately writes the exact incoming raw payload to persistent raw storage before parsing or transformation.
3. **Credential & Mapping Resolution**: System authenticates the API key hash and fetches the corresponding `ACTIVE` mapping version for the source.
4. **Normalization**: Parser applies the active mapping to transform vendor-specific fields (e.g., `src_ip`, `remote_addr`) into canonical fields (e.g., `source_ip`).
5. **Analytical Storage**: Normalized event record is inserted into ClickHouse for querying and dashboard analytics.

---

## Tech Stack

| Layer | Technology |
| :--- | :--- |
| **Core Engine** | Java 21 LTS + Spring Boot 4.1.0 (Maven 3.9.x) |
| **Control-Plane DB** | SQLite (via `sqlite-jdbc` 3.49.1.0) |
| **Event Storage** | ClickHouse 26.3 LTS (Podman container) |
| **AI Mapping Engine** | 4-Layer Hybrid Cascade (Dictionary, TF-IDF, Typo Match, Local ONNX `all-MiniLM-L6-v2`) |
| **Frontend** | React + Node.js |
| **Containerization** | Podman / Podman-Compose |

---

## Architecture

ULPF uses a strict separation between the **Control Plane** (infrequent management operations, user accounts, schema proposals, mapping state) and the **Data Plane** (high-throughput log processing, raw preservation, normalization, and analytical storage). SQLite manages control-plane metadata for fast embedded execution, while ClickHouse handles event storage. For full details on the 4-layer AI mapping engine and memory lifecycle, see [docs/MAPPING_ENGINE.md](docs/MAPPING_ENGINE.md).

```text
[ Vendor / App ] ---> POST /v1/events ---> [ Core Engine Data Plane ] ---> ( Raw Storage )
                                                   |
                                            (Active Mapping)
                                                   |
                                                   v
                                         [ ClickHouse Event DB ]

[ Admin / Vendor ] ---> Control API / UI ---> [ Core Engine Control Plane ] ---> [ SQLite DB ]
                                                       |
                                               [ AI Mapping Engine ]
```

---

## Project Structure

```text
.
├── .env.example
├── .gitignore
├── .gitattributes
├── README.md
├── compose.yaml                          # Root Podman/Docker Compose orchestrator (clickhouse + core-engine)
│
├── infra/                                # Infrastructure & ClickHouse container files
│   ├── Containerfile                     # Standalone ClickHouse container builder
│   ├── clickhouse-init/
│   │   └── 01_raw_events.sql             # ClickHouse DB & raw_events table DDL
│   └── clickhouse-config/
│       └── users.d/
│           └── async_inserts.xml         # Async inserts & memory batching configuration
│
├── docs/                                 # Project documentation & guides
│   ├── Your_first/
│   │   ├── FILE_GUIDE.md                 # Detailed file & folder guide
│   │   ├── TEAMMATE_ONBOARDING.md        # Teammate onboarding guide
│   │   └── ULPF_Dev_Environment_Setup.md # Dev environment setup
│   ├── db/
│   │   └── COMMON_DB_GUIDE.md            # Centralized DB package com.ulpf.common.db specification & reference
│   ├── API_SPECIFICATION.md
│   ├── ARCHITECTURE.md
│   ├── DATABASE_SCHEMA.md
│   ├── MAPPING_ENGINE.md                 # 4-Layer AI Mapping Engine & ONNX Model Lifecycle specification
│   ├── EVERYTHING_THAT_NEEDS_TO_BE_DONE_BEFORE_PROTOTYPE_SUB.md
│   └── PROTOTYPE_TECHNICAL_DESIGN.md
│
├── core-engine/                          # Java 21 + Spring Boot 4.1.0 Backend
│   ├── Containerfile                     # Multi-stage container build file (Maven builder + JRE 21)
│   ├── .dockerignore
│   ├── pom.xml                           # Spring Boot Maven POM configuration
│   └── src/main/
│       ├── java/com/ulpf/
│       │   ├── UlpfApplication.java      # Main application entrypoint
│       │   ├── common/                   # Spring Security & JWT auth infrastructure
│       │   │   ├── JwtUtil.java
│       │   │   ├── JwtFilter.java
│       │   │   ├── SpringSecurity.java
│       │   │   └── UlpfPrincipal.java
│       │   ├── controlplane/             # Control-plane models, repositories, & controllers
│       │   ├── dataplane/                # Data-plane ingestion & mapping services
│       │   ├── mapping/                  # Mapping engine logic
│       │   └── analytics/                # Analytics query engine
│       └── resources/
│           ├── sqlite/
│           │   └── schema.sql            # Consolidated SQLite control-plane DDL
│           ├── application.yaml          # Base application configuration
│           └── application-dev.yaml      # Development profile settings
│
├── dev-tools/                            # Development and seeder utilities
│   ├── analytics_demo.py
│   ├── ml_demo.py
│   └── seed_control_plane.py
│
└── frontend/                             # React Web Application
    ├── Containerfile
    ├── package.json
    └── vite.config.js
```

---

## Setup / Getting Started

For full cross-platform setup details, see [docs/Your_first/ULPF_Dev_Environment_Setup.md](docs/Your_first/ULPF_Dev_Environment_Setup.md).

### Quick Start (Podman / Docker)

1. **Spin up complete environment (ClickHouse + Core Engine)**:
   ```bash
   podman-compose up --build
   ```
   *(Or `podman compose up --build` / `docker compose up --build`)*

2. **Run ClickHouse only**:
   ```bash
   podman-compose up clickhouse
   ```

3. **Run Spring Boot Core Engine locally (Development Mode)**:
   ```bash
   cd core-engine
   mvn spring-boot:run
   ```
   *Note: Spring Boot automatically initializes the SQLite schema on startup from `classpath:sqlite/schema.sql`.*

---

## API Endpoints

| Method | Endpoint | Description | Status |
| :--- | :--- | :--- | :--- |
| `POST` | `/v1/login` | Authenticate user and issue JWT token | Implemented |
| `POST` | `/v1/signup` | Register new user account | Planned |
| `POST` | `/v1/onboard` | Submit new vendor/source onboarding request with sample payload | Planned |
| `GET` | `/v1/notifications` | Retrieve user notifications and onboarding request status | Planned |
| `POST` | `/v1/events` | Runtime log ingestion endpoint for onboarded sources | Planned |

---

## Known Setup Gotchas

1. **Podman Socket Not Running**: Podman CLI requires the user socket service active on Linux:
   ```bash
   systemctl --user enable --now podman.socket
   ```
2. **Podman Search Registries**: Specify full container image domains (e.g. `docker.io/clickhouse/clickhouse-server:26.3`) when building with Podman.
3. **SQLite Foreign Keys Enforcement**: SQLite disables foreign key enforcement by default on new connections. The JDBC URL includes `?foreign_keys=on` (e.g., `jdbc:sqlite:./data/control-plane.db?foreign_keys=on`) to enforce schema foreign key constraints.

---

## License

Prototype developed for SIH. License TBD.
