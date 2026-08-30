# Universal Log Framework (ULPF)

Universal Log Framework (ULPF) is a plug-and-play log ingestion platform designed to eliminate manual schema integration friction for multi-vendor environments. Vendors register and submit sample log payloads, after which an AI mapping engine analyzes the data to propose semantic mappings into a canonical log schema. A human administrator reviews, edits, and approves the proposal before an ingestion API key is granted. Once onboarded, vendors send logs through a single runtime endpoint where incoming raw logs are preserved losslessly, normalized against the active mapping version, and stored in high-performance analytical storage.

For detailed system design and architectural specifications, see [docs/ARCHITECTURE.md](file:///home/abhinav/Desktop/ULPF/docs/ARCHITECTURE.md) and [docs/ULPF_Prototype_Technical_Design.md](file:///home/abhinav/Desktop/ULPF/docs/ULPF_Prototype_Technical_Design.md).

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
| **Ingestion / Buffer** | Vector 0.55.0 |
| **AI Mapping Engine** | `llama.cpp` HTTP sidecar + Qwen2.5-3B-Instruct / Phi-3.5-mini GGUF |
| **Analytics Service** | Python 3.13 + FastAPI (`uv`, `clickhouse-connect`) |
| **Frontend** | React 19 + Node.js 24 LTS |
| **Containerization** | Podman |

---

## Architecture

ULPF uses a strict separation between the **Control Plane** (infrequent management operations, user accounts, schema proposals, mapping state) and the **Data Plane** (high-throughput log processing, raw preservation, normalization, and analytical storage). SQLite manages control-plane metadata for fast embedded execution, while ClickHouse handles event storage.

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

Below is the current repository layout. Components currently scaffolded as empty directory structures or initial placeholders are marked accordingly.

```text
.
├── .env.example
├── .gitignore
├── .gitattributes
├── README.md
├── ARCHITECTURE.md (in docs/)
│
├── infra/
│   ├── compose.yaml                      # Podman Compose service definitions (ClickHouse)
│   ├── clickhouse-init/                  # ClickHouse initialization scripts (*.sql)
│   ├── sqlite-init/
│   │   └── schema.sql                    # Control-plane SQLite database DDL
│   └── vector/
│       └── vector.yaml                   # Vector pipeline configuration
│
├── docs/
│   ├── EVERYTHING_DONE_BEFORE_FIRST_PUSH.md
│   ├── ULPF_Dev_Environment_Setup.md
│   ├── ULPF_Prototype_Technical_Design.md
│   ├── API_SPECIFICATION.md
│   └── DATABASE_SCHEMA.md
│
├── core-engine/                          # Java 21 + Spring Boot 4.1.0 Backend
│   ├── Containerfile
│   ├── .dockerignore
│   ├── pom.xml                           # Spring Boot Maven POM configuration
│   ├── data/
│   │   └── control-plane.db              # SQLite embedded database file
│   ├── src/main/java/com/ulpf/
│   │   ├── UlpfApplication.java
│   │   ├── common/                       # [Empty package] Common utilities & helpers
│   │   ├── controlplane/
│   │   │   ├── controller/               # [Empty package] Control API controllers
│   │   │   ├── service/                  # [Empty package] Control-plane services
│   │   │   ├── repository/
│   │   │   │   └── UserRepository.java   # User data access object
│   │   │   └── model/
│   │   │       ├── Role.java             # User role enum (ADMIN, VENDOR, USER)
│   │   │       └── User.java             # User domain record
│   │   └── dataplane/
│   │       ├── controller/               # [Empty package] Runtime ingestion controller
│   │       ├── service/                  # [Empty package] Log processing & normalization
│   │       └── model/                    # [Empty package] Event models
│   └── src/main/resources/
│       ├── application.yaml              # Core engine configuration
│       └── application-dev.yaml          # Development profile settings
│
├── analytics-service/                    # Python 3.13 + FastAPI Analytics [Not built out]
│   ├── Containerfile
│   ├── .dockerignore
│   ├── pyproject.toml
│   ├── app/
│   │   ├── main.py
│   │   ├── api/
│   │   ├── clickhouse/
│   │   ├── config/
│   │   └── patterns/
│   └── tests/
│
├── frontend/                             # React 19 + Vite Web Application [Not built out]
│   ├── Containerfile
│   ├── .dockerignore
│   ├── package.json
│   ├── vite.config.js
│   ├── public/
│   └── src/
│       ├── admin/
│       ├── components/
│       ├── dashboard/
│       ├── onboarding/
│       └── services/
│
└── scripts/
    ├── init_clickhouse.sh                # ClickHouse setup helper script
    └── seed_control_plane.py             # Control-plane database seeder script
```

---

## Setup / Getting Started

For full cross-platform setup details, see [docs/ULPF_Dev_Environment_Setup.md](file:///home/abhinav/Desktop/ULPF/docs/ULPF_Dev_Environment_Setup.md).

### Quick Start (Linux / WSL2)

1. **Start ClickHouse via Podman**:
   ```bash
   podman compose up -d clickhouse
   curl http://localhost:8123/ping # Should output "Ok."
   ```

2. **Initialize SQLite Database**:
   ```bash
   mkdir -p core-engine/data
   sqlite3 core-engine/data/control-plane.db < infra/sqlite-init/schema.sql
   ```

3. **Run Core Engine**:
   ```bash
   cd core-engine
   mvn spring-boot:run
   ```

---

## API Endpoints

| Method | Endpoint | Description | Status |
| :--- | :--- | :--- | :--- |
| `POST` | `/v1/login` | Authenticate user and issue session token | Planned |
| `POST` | `/v1/onboard` | Submit new vendor/source onboarding request with sample payload | Planned |
| `GET` | `/v1/notifications` | Retrieve user notifications and onboarding request status | Planned |
| `POST` | `/v1/events` | Runtime log ingestion endpoint for onboarded sources | Planned |

*Note: Data access layers are currently being built out. Controllers for the above endpoints will be added in upcoming modules.*

---

## Known Setup Gotchas

1. **Podman Socket Not Running**: Podman CLI requires the user socket service active on Linux:
   ```bash
   systemctl --user enable --now podman.socket
   ```
2. **Podman Compose Environment Loading**: `podman-compose` must be executed from the repository root directory where `.env` resides so container configuration variables resolve.
3. **Windows Maven JAVA_HOME Detection**: On Windows, Maven may fail to auto-detect JDK 21 unless `JAVA_HOME` is explicitly set in environment variables pointing to JDK 21.
4. **SQLite CLI vs JDBC Driver**: Installing the `sqlite-jdbc` Maven dependency does not install the native `sqlite3` command-line utility. The `sqlite3` package must be installed separately via system package managers if manual CLI queries are needed.
5. **SQLite Foreign Keys Enforcement**: SQLite disables foreign key enforcement by default on new connections. The JDBC URL must explicitly include `?foreign_keys=on` (e.g., `jdbc:sqlite:./data/control-plane.db?foreign_keys=on`) to enforce schema foreign key constraints.

---

## License

Prototype developed for SIH. License TBD.
