# ULPF Deployment, Startup Scripts & CI/CD Pipeline Guide

This implementation guide details the startup scripts (`start.sh`, `start.bat`), upgraded container orchestration (`compose.yaml` with healthchecks), and GitHub Actions CI/CD pipeline (`.github/workflows/ci-cd.yml`) for the ULPF platform.

Teammates can copy and implement these files directly into the repository.

---

## 📁 Required Directory Structure & File Map

```text
ULPF/
├── start.sh                           # Linux / macOS startup script
├── start.bat                          # Windows CMD / PowerShell startup script
├── compose.yaml                       # Upgraded Podman/Docker compose with Healthchecks
│
├── .github/
│   └── workflows/
│       └── ci-cd.yml                  # GitHub Actions CI/CD Pipeline (Tests + GHCR Publish)
│
└── docs/
    └── CONTAINER_AND_CICD_GUIDE.md    # This delegation guide
```

---

## 1. Linux & macOS Startup Script (`start.sh`)

Create file: `start.sh` in the root repository folder.

```bash
#!/usr/bin/env bash
# ==============================================================================
# ULPF — Linux / macOS Quick-Start Script
# ==============================================================================
set -e

echo "🚀 Starting ULPF Platform Environment..."

# 1. Ensure required local storage directories exist
echo "📁 Checking storage directories..."
mkdir -p core-engine/data core-engine/storage

# 2. Check for .env file
if [ ! -f .env ]; then
  echo "⚠️  No .env file found. Creating .env from .env.example..."
  cp .env.example .env
fi

# 3. Detect container orchestrator CLI
if command -v podman-compose &> /dev/null; then
    COMPOSE_CMD="podman-compose"
elif podman compose version &> /dev/null; then
    COMPOSE_CMD="podman compose"
elif docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
else
    echo "❌ Error: Neither podman-compose, podman compose, nor docker compose was found."
    echo "Please install Podman or Docker to continue."
    exit 1
fi

echo "📦 Using container orchestrator: $COMPOSE_CMD"

# 4. Spin up container stack
echo "⚙️  Building and launching containers..."
$COMPOSE_CMD up --build -d

# 5. Wait for frontend & backend health readiness
echo "⏳ Waiting for backend healthcheck (http://localhost:8080/v1/notifications)..."
MAX_RETRIES=30
RETRIES=0

until curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/v1/notifications | grep -qE "(200|401|403)" || [ $RETRIES -eq $MAX_RETRIES ]; do
  sleep 2
  RETRIES=$((RETRIES+1))
  echo -n "."
done

echo ""
if [ $RETRIES -eq $MAX_RETRIES ]; then
  echo "⚠️  Backend container is taking longer than expected to initialize. Check logs with: $COMPOSE_CMD logs core-engine"
else
  echo "✅ Core Engine initialized!"
fi

echo ""
echo "=========================================================================="
echo "🎉 ULPF Platform is READY!"
echo "--------------------------------------------------------------------------"
echo "  Frontend App:     http://localhost:3000"
echo "  Core Engine API:  http://localhost:8080/v1"
echo "  ClickHouse DB:    http://localhost:8123"
echo "=========================================================================="
```

**Make executable on Linux/macOS:**
```bash
chmod +x start.sh
```

---

## 2. Windows Startup Script (`start.bat`)

Create file: `start.bat` in the root repository folder.

```cmd
@echo off
REM ==============================================================================
REM ULPF — Windows Quick-Start Batch Script
REM ==============================================================================
TITLE ULPF Platform Startup

echo ==========================================================================
echo Starting ULPF Platform Environment...
echo ==========================================================================

REM 1. Create required local storage directories
if not exist "core-engine\data" (
    echo Creating core-engine\data directory...
    mkdir core-engine\data
)
if not exist "core-engine\storage" (
    echo Creating core-engine\storage directory...
    mkdir core-engine\storage
)

REM 2. Check for .env file
if not exist ".env" (
    echo No .env file found. Creating .env from .env.example...
    copy .env.example .env
)

REM 3. Detect container tool (podman-compose or docker compose)
WHERE podman-compose >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    SET COMPOSE_CMD=podman-compose
    GOTO LAUNCH
)

WHERE docker >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    SET COMPOSE_CMD=docker compose
    GOTO LAUNCH
)

echo ❌ Error: Neither podman-compose nor docker compose was found in PATH.
echo Please install Podman Desktop or Docker Desktop for Windows.
pause
exit /b 1

:LAUNCH
echo 📦 Using container orchestrator: %COMPOSE_CMD%
echo ⚙️  Building and launching containers...

%COMPOSE_CMD% up --build -d

echo ==========================================================================
echo 🎉 ULPF Platform is launching!
echo --------------------------------------------------------------------------
echo   Frontend App:     http://localhost:3000
echo   Core Engine API:  http://localhost:8080/v1
echo   ClickHouse DB:    http://localhost:8123
echo ==========================================================================
pause
```

---

## 3. Upgraded Container Compose (`compose.yaml`)

Update file: `compose.yaml` in root folder to include **Healthchecks** and strict service health dependency ordering.

```yaml
services:
  clickhouse:
    build:
      context: ./infra
      dockerfile: Containerfile
    container_name: ulpf-clickhouse
    ports:
      - "8123:8123"
      - "9000:9000"
    environment:
      CLICKHOUSE_DB: ${CLICKHOUSE_DB:-ulpf_raw}
      CLICKHOUSE_USER: ${CLICKHOUSE_USER:-default}
      CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:-}
    volumes:
      - clickhouse-data:/var/lib/clickhouse
    ulimits:
      nofile:
        soft: 262144
        hard: 262144
    healthcheck:
      test: ["CMD-SHELL", "wget --no-verbose --tries=1 --spider http://localhost:8123/ping || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 10
      start_period: 5s
    networks:
      - ulpf-net

  core-engine:
    build:
      context: ./core-engine
      dockerfile: Containerfile
    container_name: ulpf-core-engine
    ports:
      - "8080:8080"
    environment:
      - SQLITE_DB_PATH=/app/data/control-plane.db
      - CLICKHOUSE_URL=http://clickhouse:8123
      - JWT_SECRET=${JWT_SECRET:-super-secret-key-for-dev-environment-12345}
      - API_KEY_HASH_SALT=${API_KEY_HASH_SALT:-dev-salt-key-12345}
    volumes:
      - sqlite-data:/app/data
    healthcheck:
      test: ["CMD-SHELL", "wget --no-verbose --tries=1 --spider http://localhost:8080/v1/notifications || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 10
      start_period: 10s
    depends_on:
      clickhouse:
        condition: service_healthy
    networks:
      - ulpf-net

  frontend:
    build:
      context: ./frontend
      dockerfile: Containerfile
    container_name: ulpf-frontend
    ports:
      - "3000:80"
    healthcheck:
      test: ["CMD-SHELL", "wget --no-verbose --tries=1 --spider http://localhost:80/ || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 5
    depends_on:
      core-engine:
        condition: service_healthy
    networks:
      - ulpf-net

volumes:
  clickhouse-data:
  sqlite-data:

networks:
  ulpf-net:
    driver: bridge
```

---

## 4. GitHub Actions CI/CD Pipeline (`.github/workflows/ci-cd.yml`)

Create file: `.github/workflows/ci-cd.yml`.

This workflow automatically:
1. Triggers on every **Pull Request** or **Push** to `main` / `feature/*`.
2. Sets up JDK 21 and runs the full Maven test suite (`mvn clean test`).
3. Sets up Node.js and builds the React frontend (`npm run build`).
4. Builds production multi-stage container images and pushes them to **GitHub Container Registry (GHCR)** when pushed to `main`.

```yaml
name: ULPF CI/CD Pipeline

on:
  push:
    branches:
      - main
      - "feature/*"
      - "ci-cd-implementation"
    paths-ignore:
      - '**.md'
      - 'docs/**'
      - '.gitignore'
  pull_request:
    branches:
      - main
    paths-ignore:
      - '**.md'
      - 'docs/**'
      - '.gitignore'

env:
  REGISTRY: ghcr.io
  IMAGE_PREFIX: ${{ github.repository }}

jobs:
  # ---------------------------------------------------------------------------
  # JOB 1: Test & Verify Backend Core Engine
  # ---------------------------------------------------------------------------
  test-backend:
    name: Build & Test Core Engine (Java 21)
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      - name: Run Unit & Integration Tests
        run: |
          cd core-engine
          mvn clean test -DJWT_SECRET="test-secret-key-1234567890"

  # ---------------------------------------------------------------------------
  # JOB 2: Build & Verify React Frontend
  # ---------------------------------------------------------------------------
  test-frontend:
    name: Build & Verify Frontend (React)
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4

      - name: Set up Node.js 21
        uses: actions/setup-node@v4
        with:
          node-version: '21'
          cache: 'npm'
          cache-dependency-path: frontend/package-lock.json

      - name: Install & Build Frontend
        run: |
          cd frontend
          npm ci
          npm run build

  # ---------------------------------------------------------------------------
  # JOB 3: Build & Publish Container Images to GHCR (Main Branch Only)
  # ---------------------------------------------------------------------------
  publish-containers:
    name: Build & Push Container Images (GHCR)
    needs: [test-backend, test-frontend]
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4

      - name: Lowercase Repository Name for GHCR
        run: |
          echo "IMAGE_PREFIX=$(echo "${{ github.repository }}" | tr '[:upper:]' '[:lower:]')" >> $GITHUB_ENV

      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      # Build & Push Core Engine Container
      - name: Build & Push Core Engine Image
        uses: docker/build-push-action@v6
        with:
          context: ./core-engine
          file: ./core-engine/Containerfile
          push: true
          tags: |
            ${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}/core-engine:latest
            ${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}/core-engine:${{ github.sha }}

      # Build & Push Frontend Container
      - name: Build & Push Frontend Image
        uses: docker/build-push-action@v6
        with:
          context: ./frontend
          file: ./frontend/Containerfile
          push: true
          tags: |
            ${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}/frontend:latest
            ${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}/frontend:${{ github.sha }}
```

---

## 🛠️ Instructions for Teammate / Assignee

1. **Add `start.sh` & `start.bat`**: Place in root directory and grant execution permission (`chmod +x start.sh`).
2. **Update `compose.yaml`**: Replace root `compose.yaml` with the upgraded version above containing healthchecks.
3. **Add `.github/workflows/ci-cd.yml`**: Create directory `.github/workflows/` and save `ci-cd.yml`.
4. **Test Local Startup**:
   - On Linux/macOS: `./start.sh`
   - On Windows: `start.bat`
5. **Verify CI Pipeline**: Push changes to GitHub and confirm the **Actions tab** passes all tests and container builds.
