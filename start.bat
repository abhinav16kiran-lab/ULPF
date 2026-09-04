@echo off
TITLE ULPF Platform Startup

echo ==========================================================================
echo Starting ULPF Platform Environment...
echo ==========================================================================

REM Create required local storage directories
if not exist "core-engine\data" (
    echo Creating core-engine\data directory...
    mkdir core-engine\data
)

if not exist "core-engine\storage" (
    echo Creating core-engine\storage directory...
    mkdir core-engine\storage
)

REM Create .env from .env.example if it does not exist
if not exist ".env" (
    echo No .env file found. Creating .env from .env.example...
    copy .env.example .env
)

REM Detect Podman Compose
WHERE podman-compose >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    SET COMPOSE_CMD=podman-compose
    GOTO LAUNCH
)

REM Detect Docker
WHERE docker >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    SET COMPOSE_CMD=docker compose
    GOTO LAUNCH
)

echo Error: Neither Podman Compose nor Docker Compose was found in PATH.
echo Please install Podman Desktop or Docker Desktop.
pause
exit /b 1

:LAUNCH
echo Using container orchestrator: %COMPOSE_CMD%

echo Building and launching containers...
%COMPOSE_CMD% up --build -d

echo ==========================================================================
echo ULPF Platform is launching!
echo --------------------------------------------------------------------------
echo Frontend App:     http://localhost:3000
echo Core Engine API:  http://localhost:8080/v1
echo ClickHouse DB:    http://localhost:8123
echo ==========================================================================

pause
