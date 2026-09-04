# ULPF Container & CI/CD Implementation

## Overview

This document records the container and CI/CD work completed for the ULPF platform.

## 1. Startup Scripts

Added one-command startup scripts for Linux/macOS and Windows.

### `start.sh`

- Creates required storage directories.
- Creates `.env` from `.env.example` if needed.
- Detects the available container compose command.
- Builds and starts the services.
- Waits for the Core Engine.
- Displays service URLs.

### `start.bat`

- Creates required storage directories.
- Creates `.env` from `.env.example` if needed.
- Detects Podman Compose or Docker Compose.
- Builds and starts the containers.
- Displays service URLs.

## 2. Compose Configuration

Upgraded `compose.yaml` with:

- ClickHouse
- Core Engine
- Frontend
- Persistent volumes
- Dedicated `ulpf-net` network
- Healthchecks
- Health-based service dependencies

Startup order:

1. ClickHouse becomes healthy.
2. Core Engine starts after ClickHouse.
3. Frontend starts after Core Engine.

## 3. GitHub Actions CI Pipeline

Added:

`.github/workflows/ci-cd.yml`

### Backend

- Java 21
- Maven
- `mvn clean test`

### Frontend

- Node.js 21
- `npm ci`
- `npm run build`

The workflow automatically checks the backend and frontend on pushes and pull requests.

## 4. Container Publishing

On pushes to `main`, after successful checks, the workflow builds and publishes:

- Core Engine container
- Frontend container

Images are published to GitHub Container Registry with `latest` and commit-SHA tags.

## 5. Verification

The GitHub Actions pipeline was tested successfully.

The latest workflow run after the rebase completed successfully.

## 6. Rebase

The `ci-cd-implementation` branch was rebased onto the latest `main` and pushed successfully using:

```bash
git push --force-with-lease origin ci-cd-implementation
