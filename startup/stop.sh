#!/usr/bin/env bash

set -e

# Ensure we run from the project root directory
cd "$(dirname "$0")/.."

echo "Stopping ULPF Platform Environment..."

if command -v podman-compose &> /dev/null; then
    COMPOSE_CMD="podman-compose"
elif podman compose version &> /dev/null; then
    COMPOSE_CMD="podman compose"
elif docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
else
    COMPOSE_CMD="docker compose"
fi

$COMPOSE_CMD down

echo "[✓] All ULPF containers stopped successfully!"
