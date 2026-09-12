# ULPF — File & Repository Guide

This document details the exact repository structure and responsibilities for every directory and key file in ULPF.

---

## 1. Repository Root

```text
ULPF/
├── compose.yaml          # Master Podman/Docker Compose orchestrator
├── start.sh              # Single-command Linux/macOS startup script with health checks
├── start.bat             # Single-command Windows startup script
├── README.md             # Primary project overview & quickstart documentation
├── MIGRATION.md          # Enterprise log platform migration guide
├── core-engine/          # Java Spring Boot backend engine
├── dev-tools/            # Performance load testing & benchmarking tools
├── docs/                 # System architecture, API specs & DB schemas
├── frontend/             # React SPA frontend application
└── infra/                # Containerfiles & ClickHouse initialization scripts
```

---

## 2. Directory & Module Breakdown

### 2.1 `core-engine/` (Java Spring Boot Backend)

```text
core-engine/
├── src/main/java/com/ulpf/
│   ├── admin/            # Admin onboarding approval controllers & schema management
│   ├── analytics/        # ClickHouse query execution, time-series & dynamic schema service
│   ├── auth/             # Authentication, user registration & JWT token security
│   ├── common/           # DB connection pools, JWT utilities & global security filter
│   ├── ingestion/        # High-throughput event ingestion & format autodetector
│   ├── integrity/        # Cryptographic Merkle tree forensic audit engine
│   ├── mapping/          # 4-layer AI schema mapping engine & human feedback loop
│   ├── notification/     # Notification feed & API key delivery service
│   └── onboard/          # Vendor log onboarding form processing & file upload
├── src/main/resources/
│   ├── sqlite/schema.sql # Baseline SQLite control plane DDL script
│   └── application.yaml  # Spring Boot configuration
├── models/               # Local ONNX all-MiniLM-L6-v2 vector embedding model
├── Containerfile         # Multi-stage Java container build file
└── pom.xml               # Maven dependencies and build config
```

### 2.2 `frontend/` (React SPA Web UI)

```text
frontend/
├── src/
│   ├── assets/           # Logo SVG assets & empty state vector illustrations
│   ├── components/       # Reusable UI components (Navbar, UlpfLogo, EmptyState)
│   ├── pages/            # View pages (Login, Signup, Onboard, Admin, Notifications, Integrity, Analytics)
│   ├── routes/           # ProtectedRoute wrapper enforcing JWT role-based access
│   ├── App.jsx           # Master React Router navigation & route definitions
│   └── index.css         # Warm Kinetic design tokens & Tailwind CSS styles
├── Containerfile         # Multi-stage Vite build + Nginx Alpine runner
└── nginx.conf            # Edge web server SPA fallback & API proxy configuration
```

### 2.3 `dev-tools/` (Performance & Benchmarking Suite)

```text
dev-tools/
├── ramp_benchmark_graph.py  # Multi-threaded load tester (10 to 10,000+ EPS ramp-up)
├── load_test.py             # Latency (p50, p95, p99) & throughput (MB/s) telemetry harness
├── make_graphs.gp           # Gnuplot script generating production performance graphs
├── latency_graph.png        # Generated latency percentile graph
└── throughput_graph.png     # Generated throughput benchmark graph
```

### 2.4 `infra/` (Infrastructure & Container Services)

```text
infra/
├── clickhouse-config/users.d/async_inserts.xml  # ClickHouse async micro-batching config
├── clickhouse-init/01_raw_events.sql           # Database & raw_events table DDL
└── Containerfile                                # Standalone ClickHouse container image build
```

### 2.5 `docs/` (Project Documentation)

```text
docs/
├── ARCH/
│   ├── API_SPECIFICATION.md            # Complete REST API reference
│   ├── ARCHITECTURE.md                 # System architecture & data flow diagrams
│   ├── DISTRIBUTED_TRACING_ARCHITECTURE.md # Distributed tracing & MDC correlation
│   ├── FILE_GUIDE.md                   # This file; repository structure reference
│   ├── MAPPING_ENGINE.md               # 4-layer AI mapping engine specification
│   └── SCALABILITY_AND_CLUSTER_GUIDE.md# ClickHouse scaling & cluster design
├── db/
│   ├── COMMON_DB_GUIDE.md              # Centralized database package reference
│   └── DATABASE_SCHEMA.md              # Complete SQLite & ClickHouse database schemas
├── frontend/
│   └── frontend.md                     # React frontend architecture & Nginx specs
└── MIGRATION.md                        # Enterprise log migration guide
```
