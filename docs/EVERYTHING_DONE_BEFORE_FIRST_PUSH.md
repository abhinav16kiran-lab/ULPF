# Everything Done Before First Push

## Infrastructure
- **ClickHouse 26.3 LTS**: Running via Podman container (`ulpf-clickhouse`), verified reachable at `http://localhost:8123/ping`.
- **Podman Setup Fix**: Enabled systemd user socket (`systemctl --user enable --now podman.socket`) and configured running `podman compose` from repo root so environment variables are loaded cleanly from `.env`.

## Core Engine (Spring Boot)
- **Framework & Runtime**: Scaffolded `core-engine/` as a Spring Boot 4.1.0 project targeting Java 21 LTS.
- **Maven Dependencies ([pom.xml](file:///home/abhinav/Desktop/ULPF/core-engine/pom.xml))**:
  - `spring-boot-starter-web`
  - `spring-boot-starter-validation`
  - `spring-boot-starter-jdbc`
  - `org.xerial:sqlite-jdbc` (version `3.49.1.0`)
  - `spring-boot-starter-test`
- **Package Architecture**:
  - `com.ulpf.controlplane/{controller, service, repository, model}`
  - `com.ulpf.dataplane/{controller, service, model}`
  - `com.ulpf.common`
- **Configuration ([application.yaml](file:///home/abhinav/Desktop/ULPF/core-engine/src/main/resources/application.yaml) & [application-dev.yaml](file:///home/abhinav/Desktop/ULPF/core-engine/src/main/resources/application-dev.yaml))**:
  - `server.port`: Configured via `${CORE_ENGINE_PORT:8080}`.
  - `spring.datasource.url`: Configured via `jdbc:sqlite:${SQLITE_DB_PATH:./data/control-plane.db}?foreign_keys=on`.
  - **Foreign Keys**: Enforced via explicit `foreign_keys=on` query parameter on every JDBC connection.
  - **Profiles & Logging**: Default active profile `dev`, with DEBUG logging on `com.ulpf` and `org.springframework.jdbc`.
- **DataSource Validation**: End-to-end connection and configuration confirmed via temporary `CommandLineRunner` bean (`SELECT 1` = 1, `PRAGMA foreign_keys` = 1).

## Control-Plane Database (SQLite)
- **Schema Specification ([schema.sql](file:///home/abhinav/Desktop/ULPF/core-engine/sqlite-init/schema.sql))**: Created 7 core control-plane tables:
  1. `users`
  2. `vendors`
  3. `sources`
  4. `credentials`
  5. `mapping_versions`
  6. `onboarding_requests`
  7. `notifications`
- **Constraints & Integrity**: Real foreign key constraints, `CHECK` constraints on status/role columns, and composite `UNIQUE(source_id, version)` constraint on `mapping_versions`.
- **Indexes**: Added index on `credentials.key_hash` for fast authentication on ingestion requests, and index on `mapping_versions(source_id, status)` for active mapping lookups.
- **Physical Verification**: Applied schema against `data/control-plane.db` and verified table creation using `sqlite3 .tables`.

## Application Code
- **Domain Models ([com.ulpf.controlplane.model](file:///home/abhinav/Desktop/ULPF/core-engine/src/main/java/com/ulpf/controlplane/model))**:
  - [Role.java](file:///home/abhinav/Desktop/ULPF/core-engine/src/main/java/com/ulpf/controlplane/model/Role.java) enum (`ADMIN`, `VENDOR`, `USER`).
  - [User.java](file:///home/abhinav/Desktop/ULPF/core-engine/src/main/java/com/ulpf/controlplane/model/User.java) Java 21 Record (`userId`, `username`, `passwordHash`, `role`, `createdAt`).
- **Repositories ([com.ulpf.controlplane.repository](file:///home/abhinav/Desktop/ULPF/core-engine/src/main/java/com/ulpf/controlplane/repository))**:
  - [UserRepository.java](file:///home/abhinav/Desktop/ULPF/core-engine/src/main/java/com/ulpf/controlplane/repository/UserRepository.java): Pure JDBC data access layer with constructor-injected `JdbcTemplate`.
  - Implemented `save(User)` (UUID generation if omitted), `findById(String)`, `findByUsername(String)`, and `existsByUsername(String)`.

## Requirements
- Comprehensive project requirements document drafted separately, covering functional & non-functional requirements, technology stack, data model, API surface, security minimums, out-of-scope items, and demo acceptance criteria.

## Not Yet Done
- **`UserService`**: Password hashing and authentication business logic.
- **Remaining Repositories**: `VendorRepository`, `SourceRepository`, `CredentialRepository`, `MappingVersionRepository`, `OnboardingRequestRepository`, `NotificationRepository`.
- **Controllers & Endpoints**: `POST /v1/login`, `POST /v1/onboard`, `GET /v1/notifications`, `POST /v1/events`.
- **AI Mapping Engine**: `llama.cpp` sidecar integration.
- **Vector Pipeline**: Ingestion and buffering setup.
- **Frontend**: React 19 web interface.
