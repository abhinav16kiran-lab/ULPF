#!/usr/bin/env bash

set -e

echo "Starting ULPF Platform Environment..."

echo "Checking storage directories..."
mkdir -p core-engine/data core-engine/storage

if [ ! -f .env ]; then
    echo "No .env file found. Creating .env from .env.example..."
    cp .env.example .env
    if [ -t 0 ]; then
        echo ""
        read -p "Would you like to customize Admin & DB credentials now? [y/N]: " custom_env
        if [[ "$custom_env" =~ ^[Yy]$ ]]; then
            read -p "Enter Admin Username [admin]: " input_admin_user
            read -sp "Enter Admin Password [Admin@12345]: " input_admin_pass
            echo ""
            read -p "Enter ClickHouse Username [default]: " input_ch_user
            read -sp "Enter ClickHouse Password [Clickhouse123!]: " input_ch_pass
            echo ""

            [ -n "$input_admin_user" ] && sed -i "s/ULPF_ADMIN_USERNAME=.*/ULPF_ADMIN_USERNAME=$input_admin_user/" .env
            [ -n "$input_admin_pass" ] && sed -i "s/ULPF_ADMIN_PASSWORD=.*/ULPF_ADMIN_PASSWORD=$input_admin_pass/" .env
            [ -n "$input_ch_user" ] && sed -i "s/CLICKHOUSE_USER=.*/CLICKHOUSE_USER=$input_ch_user/" .env
            [ -n "$input_ch_pass" ] && sed -i "s/CLICKHOUSE_PASSWORD=.*/CLICKHOUSE_PASSWORD=$input_ch_pass/" .env
            echo "Updated .env with your custom credentials!"
        fi
    fi
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
    echo "=========================================================================="
    echo "ERROR: Neither Podman nor Docker container orchestrator was found!"
    echo "--------------------------------------------------------------------------"
    echo "Please install one of the following to run ULPF:"
    echo "  - Docker Engine / Desktop: https://docs.docker.com/get-docker/"
    echo "  - Podman / Podman Desktop: https://podman.io/"
    echo "=========================================================================="
    exit 1
fi

echo "Using container orchestrator: $COMPOSE_CMD"

echo "Building and launching container services..."
if [[ "$*" == *"--no-cache"* ]]; then
    echo "Force clean rebuild requested (bypassing layer cache)..."
    if [[ "$COMPOSE_CMD" == *"podman-compose"* ]]; then
        $COMPOSE_CMD --podman-build-args="--no-cache" build
        $COMPOSE_CMD down
        $COMPOSE_CMD up -d
    else
        $COMPOSE_CMD build --no-cache
        $COMPOSE_CMD up --force-recreate -d
    fi
else
    if [[ "$COMPOSE_CMD" == *"podman-compose"* ]]; then
        $COMPOSE_CMD down
        $COMPOSE_CMD up --build -d
    else
        $COMPOSE_CMD up --build --force-recreate -d
    fi
fi
echo "[✓] Container build and launch complete!"

echo "Verifying service readiness..."

MAX_RETRIES=40
RETRIES=0

ENGINE_READY=0
FRONTEND_READY=0

until [ $RETRIES -eq $MAX_RETRIES ]; do
    if [ $ENGINE_READY -eq 0 ]; then
        if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/v1/health 2>/dev/null | grep -qE "200"; then
            ENGINE_READY=1
        fi
    fi

    if [ $FRONTEND_READY -eq 0 ]; then
        if curl -s -o /dev/null -w "%{http_code}" http://localhost:3000 2>/dev/null | grep -qE "200|304"; then
            FRONTEND_READY=1
        fi
    fi

    if [ $ENGINE_READY -eq 1 ] && [ $FRONTEND_READY -eq 1 ]; then
        break
    fi

    sleep 2
    RETRIES=$((RETRIES+1))
    printf "\rWaiting for services... [%d/%d]" "$RETRIES" "$MAX_RETRIES"
done

echo ""

if [ $ENGINE_READY -eq 1 ] && [ $FRONTEND_READY -eq 1 ]; then
    echo "All ULPF services initialized successfully!"
    echo ""
    echo "============================================================"
    echo "ULPF Platform is READY!"
    echo "------------------------------------------------------------"
    echo "Frontend App:     http://localhost:3000"
    echo "Core Engine API:  http://localhost:8080/v1"
    echo "ClickHouse DB:    http://localhost:8123"
    echo "============================================================"
else
    echo "============================================================"
    echo "WARNING: One or more services took longer than expected to start."
    echo "------------------------------------------------------------"
    echo "Check active container status with: $COMPOSE_CMD ps"
    echo "Inspect container logs with:       $COMPOSE_CMD logs"
    echo "============================================================"
fi
