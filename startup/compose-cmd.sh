#!/usr/bin/env bash
# Sourced by the startup scripts: sets COMPOSE_CMD to the first container orchestrator found, or exits.

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
