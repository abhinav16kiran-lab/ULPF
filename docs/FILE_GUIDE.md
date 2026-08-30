# ULPF — What Each File Does

A plain description of every file and folder in the project. No jargon, just what it's for.

---

## Root

| File | What it does |
|---|---|
| `README.md` | The front page of the project. Explains what ULPF is, how the flow works, the tech stack, and how to get it running. |
| `ARCHITECTURE.md` (in `docs/`) | The deeper technical explanation — how control plane and data plane are split, the database structure, the mapping lifecycle. |
| `.env.example` | A template showing every setting/password the project needs (ports, DB paths, secrets), but with placeholder values. Safe to commit — shows the shape without leaking real secrets. |
| `.env` | The real version of the above, with actual passwords/secrets filled in. Never committed to git. |
| `.gitignore` | Tells git which files to never track — secrets, build output, generated database files, dependency folders. |
| `.gitattributes` | Forces consistent line endings (LF) across Windows/Linux/Mac so the same file doesn't look "changed" just because someone's on a different OS. |

---

## `docs/`

| File | What it does |
|---|---|
| `ULPF_Prototype_Technical_Design.md` | The original full design document — scope, architecture, workflows, database model, API surface, everything the project is based on. |
| `ULPF_Dev_Environment_Setup.md` | Step-by-step instructions for installing every tool needed (Java, Node, Python, Podman, etc.) on Linux or Windows. |
| `ARCHITECTURE.md` | Technical deep-dive: control plane vs data plane, diagrams of both flows, the database's entity relationships, how vendor fields map to canonical fields, the mapping version lifecycle. |
| `API_SPECIFICATION.md` | Placeholder for documenting each API endpoint in detail (request/response shapes). Empty for now — gets filled in as the endpoints are actually built. |
| `DATABASE_SCHEMA.md` | Placeholder for a human-readable writeup of the database schema. Empty for now — the real schema lives in `core-engine/sqlite-init/schema.sql`. |
| `EVERYTHING_DONE_BEFORE_FIRST_PUSH.md` | A snapshot of everything that was built and verified before the first git push — what's working, what's still just planned. |

---

## `infra/`

| File | What it does |
|---|---|
| `compose.yaml` | The Podman Compose file — defines how ClickHouse (and eventually Vector) run as containers, so `podman compose up` starts them with one command. |

---

## `core-engine/` (the Java backend — control plane + data plane)

| File | What it does |
|---|---|
| `Containerfile` | Instructions for building the core engine into a container image. |
| `.dockerignore` | Tells the container build to skip files it doesn't need (like `target/`), so the image stays small. |
| `pom.xml` | The Maven project file — lists every Java library the project depends on (Spring Boot, the SQLite driver, etc.) and how to build it. |
| `sqlite-init/schema.sql` | The actual database schema for the control-plane database — creates all 7 tables (users, vendors, sources, credentials, mapping_versions, onboarding_requests, notifications) with their relationships and rules. |
| `src/main/java/com/ulpf/UlpfApplication.java` | The entry point of the whole backend. Running this starts the Spring Boot application. |
| `src/main/java/com/ulpf/controlplane/model/Role.java` | Defines the three account types a user can have: ADMIN, VENDOR, or USER. |
| `src/main/java/com/ulpf/controlplane/model/User.java` | Defines what a "user" looks like in code (their ID, username, password hash, role, when they were created) — mirrors the `users` table. |
| `src/main/java/com/ulpf/controlplane/repository/UserRepository.java` | The code that actually talks to the database for users — saving a new user, looking one up by ID or username, checking if a username's taken. |
| `src/main/java/com/ulpf/controlplane/controller/` | Empty for now. Will hold the code that handles incoming HTTP requests related to users/vendors/onboarding (like `POST /v1/login`, `POST /v1/onboard`). |
| `src/main/java/com/ulpf/controlplane/service/` | Empty for now. Will hold the business logic layer for the control plane — e.g. password hashing and login logic, sitting between the controllers and the repository. |
| `src/main/java/com/ulpf/dataplane/controller/` | Empty for now. Will hold the code that handles the log-ingestion endpoint (`POST /v1/events`). |
| `src/main/java/com/ulpf/dataplane/service/` | Empty for now. Will hold the logic for processing incoming logs — saving the raw event, applying the mapping, normalizing it. |
| `src/main/java/com/ulpf/dataplane/model/` | Empty for now. Will hold the data shapes for events (raw and normalized). |
| `src/main/java/com/ulpf/common/` | Empty for now. Meant for code shared across both control plane and data plane (e.g. shared utilities, error handling). |
| `src/main/resources/application.yaml` | Main configuration file — sets the server port and database connection details, using environment variables with fallback defaults. |
| `src/main/resources/application-dev.yaml` | Extra settings that only apply when running in development mode — more detailed logging. |
| `src/main/resources/application-prod.yaml` | Extra settings for a production run. Not really used yet since this is still prototype-stage. |

---

## `analytics-service/` (the Python read-only reporting layer)

| File | What it does |
|---|---|
| `Containerfile` | Instructions for building the analytics service into a container image. |
| `.dockerignore` | Skips unnecessary files when building that container image. |
| `pyproject.toml` | Lists the Python dependencies (FastAPI, the ClickHouse driver) and project settings, managed by `uv`. |
| `app/main.py` | Will be the entry point of the analytics API once it's built. Currently empty — this service isn't started yet. |

---

## `frontend/` (the React web UI)

| File | What it does |
|---|---|
| `Containerfile` | Instructions for building the frontend into a container image. |
| `.dockerignore` | Skips unnecessary files (like `node_modules`) when building that image. |
| `package.json` | Lists the frontend's dependencies (React, etc.) and defines commands like "start" or "build". Currently a placeholder — the frontend hasn't been built out yet. |
| `vite.config.js` | Configuration for Vite, the tool that runs and bundles the React app. Also a placeholder for now. |

---

## `scripts/`

| File | What it does |
|---|---|
| `seed_control_plane.py` | Will be a script to load some starter/test data into the control-plane database, so you don't have to manually create test users/vendors every time. Empty for now — not written yet. |
| `init_clickhouse.sh` | Will be a script to set up ClickHouse's tables automatically. Empty for now — not written yet. |

---

*Files marked "empty for now" are placeholders that exist to show the intended project layout — no code has been written in them yet.*
