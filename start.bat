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
    echo.
    set /p CUSTOM_CRED="Would you like to customize Admin and DB credentials now? (y/N): "
    if /i "%CUSTOM_CRED%"=="y" (
        set /p IN_ADMIN_USER="Enter Admin Username [admin]: "
        set /p IN_ADMIN_PASS="Enter Admin Password [Admin@12345]: "
        set /p IN_CH_USER="Enter ClickHouse Username [default]: "
        set /p IN_CH_PASS="Enter ClickHouse Password [Clickhouse123!]: "

        if not "%IN_ADMIN_USER%"=="" powershell -Command "(Get-Content .env) -replace '^ULPF_ADMIN_USERNAME=.*', 'ULPF_ADMIN_USERNAME=%IN_ADMIN_USER%' | Set-Content .env"
        if not "%IN_ADMIN_PASS%"=="" powershell -Command "(Get-Content .env) -replace '^ULPF_ADMIN_PASSWORD=.*', 'ULPF_ADMIN_PASSWORD=%IN_ADMIN_PASS%' | Set-Content .env"
        if not "%IN_CH_USER%"=="" powershell -Command "(Get-Content .env) -replace '^CLICKHOUSE_USER=.*', 'CLICKHOUSE_USER=%IN_CH_USER%' | Set-Content .env"
        if not "%IN_CH_PASS%"=="" powershell -Command "(Get-Content .env) -replace '^CLICKHOUSE_PASSWORD=.*', 'CLICKHOUSE_PASSWORD=%IN_CH_PASS%' | Set-Content .env"
        echo Updated .env with custom credentials!
    )
)

REM Detect Podman Compose
WHERE podman-compose >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    SET COMPOSE_CMD=podman-compose
    GOTO LAUNCH
)

WHERE podman >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    SET COMPOSE_CMD=podman compose
    GOTO LAUNCH
)

REM Detect Docker
WHERE docker >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    SET COMPOSE_CMD=docker compose
    GOTO LAUNCH
)

WHERE docker-compose >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    SET COMPOSE_CMD=docker-compose
    GOTO LAUNCH
)

echo.
echo ==========================================================================
echo ERROR: Neither Podman nor Docker container orchestrator was found!
echo --------------------------------------------------------------------------
echo Please install one of the following to run ULPF:
echo   - Docker Desktop: https://www.docker.com/products/docker-desktop/
echo   - Podman Desktop: https://podman-desktop.io/
echo ==========================================================================
echo.
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
