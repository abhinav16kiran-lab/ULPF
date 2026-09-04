#!/usr/bin/env bash

set -e

echo "Starting ULPF Platform Environment..."

echo "Checking storage directories..."
mkdir -p core-engine/data core-engine/storage

if [ ! -f .env ]; then
    echo "No .env file found. Creating .env from .env.example..."
    cp .env.example .env
fi

if command -v podman-compose &> /dev/null; then
    COMPOSE_CMD="podman-compose"
elif podman compose version &> /dev/null; then
    COMPOSE_CMD="podman compose"
elif docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
else
    echo "Error: Neither Podman Compose nor Docker Compose was found."
    echo "Please install Podman or Docker."
    exit 1
fi

echo "Using container orchestrator: $COMPOSE_CMD"

echo "Building and launching containers..."
$COMPOSE_CMD up --build -d

echo "Waiting for Core Engine..."

MAX_RETRIES=30
RETRIES=0

until curl -s -o /dev/null -w "%{http_code}" \
    http://localhost:8080/v1/notifications | grep -qE "(200|401|403)" \
    || [ $RETRIES -eq $MAX_RETRIES ]; do

    sleep 2
    RETRIES=$((RETRIES+1))
    echo -n "."
done

echo ""

if [ $RETRIES -eq $MAX_RETRIES ]; then
    echo "Backend is taking longer than expected."
    echo "Check logs with:"
    echo "$COMPOSE_CMD logs core-engine"
else
    echo "Core Engine initialized successfully!"
fi

echo ""
echo "============================================================"
echo "ULPF Platform is READY!"
echo "------------------------------------------------------------"
echo "Frontend App:     http://localhost:3000"
echo "Core Engine API:  http://localhost:8080/v1"
echo "ClickHouse DB:    http://localhost:8123"
echo "============================================================"
