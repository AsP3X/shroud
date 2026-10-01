#!/usr/bin/env bash
# Stops the stack of stack-up.sh: the API it started, the three containers (their data goes with
# them) and the media folder. The server log stays in the state folder for a look afterwards.
set -euo pipefail
. "$(dirname "$0")/common.sh"

if [ -f "$STATE_DIR/server.pid" ]; then
    pid="$(cat "$STATE_DIR/server.pid")"
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        for i in $(seq 1 10); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.5
        done
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$STATE_DIR/server.pid"
fi
docker rm -f "$PREFIX-pg" "$PREFIX-redis" "$PREFIX-ntfy" >/dev/null 2>&1 || true
rm -rf "$STATE_DIR/media"
echo "stack down"
