#!/usr/bin/env bash
# Resets a lost super-admin password. Run on the server, with the stack running: ./startup/reset-super-admin.sh
# Only someone who can run container commands here can use it (they can already read the database and .env);
# there is no network endpoint for this. Regular admins' passwords are reset from the Admins screen instead.

set -e
cd "$(dirname "$0")/.."
source startup/compose-cmd.sh

if [ ! -t 0 ]; then
    echo "Run this in a terminal: it asks for the new password." >&2
    exit 1
fi

echo "Reset the super-admin password (12-100 characters)."
read -rsp "New password: " password; echo ""
read -rsp "Confirm password: " confirm; echo ""

# printf is a shell builtin, so the password never appears in the process list; it reaches the command on stdin.
printf '%s\n%s\n' "$password" "$confirm" | $COMPOSE_CMD exec -T core-engine \
    java -cp /app/app.jar -Dloader.main=com.ulpf.controlplane.cli.ResetSuperAdminCommand \
    org.springframework.boot.loader.launch.PropertiesLauncher
