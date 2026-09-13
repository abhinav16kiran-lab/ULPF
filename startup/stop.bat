@echo off
echo Stopping ULPF Platform Environment...

REM Ensure we run from the project root directory
cd /d "%~dp0\.."

docker compose down

echo [✓] All ULPF containers stopped successfully!
