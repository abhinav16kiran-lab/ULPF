#!/usr/bin/env bash

set -e

# Ensure we run from the project root directory
cd "$(dirname "$0")/.."

echo "Starting ULPF Platform Environment..."

echo "Checking storage directories..."
mkdir -p core-engine/data core-engine/storage

SETUP_PASSWORD_GENERATED=""
if [ ! -f .env ]; then
    echo "No .env file found. Creating .env from .env.example..."
    cp .env.example .env
    # One-time setup password: entered once on the setup screen, then never used again. Letters and digits
    # without look-alikes (0/O, 1/l/I), because it is typed by hand.
    SETUP_PASSWORD_GENERATED=$(LC_ALL=C tr -dc 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789' < /dev/urandom | head -c 10)
    sed -i "s/^ULPF_ADMIN_PASSWORD=.*/ULPF_ADMIN_PASSWORD=$SETUP_PASSWORD_GENERATED/" .env
    if [ -t 0 ]; then
        echo ""
        read -p "Would you like to customize the ClickHouse credentials now? [y/N]: " custom_env
        if [[ "$custom_env" =~ ^[Yy]$ ]]; then
            read -p "Enter ClickHouse Username [default]: " input_ch_user
            read -sp "Enter ClickHouse Password [Clickhouse123!]: " input_ch_pass
            echo ""

            [ -n "$input_ch_user" ] && sed -i "s/CLICKHOUSE_USER=.*/CLICKHOUSE_USER=$input_ch_user/" .env
            [ -n "$input_ch_pass" ] && sed -i "s/CLICKHOUSE_PASSWORD=.*/CLICKHOUSE_PASSWORD=$input_ch_pass/" .env
            echo "Updated .env with your custom credentials!"
        fi
    fi
elif grep -q '^ULPF_ADMIN_PASSWORD=Admin@12345$' .env; then
    echo "WARNING: .env still has the old public default ULPF_ADMIN_PASSWORD=Admin@12345."
    echo "         It is now the one-time setup password: replace it with a private value before the first setup."
fi

source startup/compose-cmd.sh

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

echo "Pruning dangling builder cache layers..."
if command -v podman &> /dev/null; then
    podman image prune -f > /dev/null 2>&1 || true
elif command -v docker &> /dev/null; then
    docker image prune -f > /dev/null 2>&1 || true
fi

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
    if [ -n "$SETUP_PASSWORD_GENERATED" ]; then
        echo "First-time setup: open http://localhost:3000/setup and enter these setup"
        echo "credentials once, then create your super-admin account:"
        echo "  Setup username: $(grep '^ULPF_ADMIN_USERNAME=' .env | cut -d= -f2-)"
        echo "  Setup password: $SETUP_PASSWORD_GENERATED   (also stored in .env)"
        echo "============================================================"
    fi
else
    echo "============================================================"
    echo "WARNING: One or more services took longer than expected to start."
    echo "------------------------------------------------------------"
    echo "Check active container status with: $COMPOSE_CMD ps"
    echo "Inspect container logs with:       $COMPOSE_CMD logs"
    echo "============================================================"
fi
