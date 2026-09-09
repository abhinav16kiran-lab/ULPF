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

echo "Building and launching containers..."
$COMPOSE_CMD up --build -d

echo "Waiting for Core Engine..."

MAX_RETRIES=30
RETRIES=0

until curl -s -o /dev/null -w "%{http_code}" \
    http://localhost:8080/v1/health | grep -qE "200" \
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
