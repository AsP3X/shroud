#!/usr/bin/env bash
# Throwaway local Shroud stack for Android e2e (00-plan §6.3, §6.5):
#   Postgres 16 on 127.0.0.1:55471, Redis 7 on 127.0.0.1:56391 and ntfy (the UnifiedPush push
#   service, no auth, in-memory cache) on 127.0.0.1:2586 in Docker; the API built from this
#   checkout with cargo on 127.0.0.1:8080, migrations run, media in a temp folder.
# Debug builds on an emulator reach the API at http://10.0.2.2:8080/api/v1 (no setup needed);
# run emulator-setup.sh once per emulator for the screen lock, Android 17's local-network
# permission and the ntfy port. Ports, names and paths: see common.sh. Stop with stack-down.sh.
set -euo pipefail
. "$(dirname "$0")/common.sh"

command -v cargo >/dev/null || die "cargo not found (the API is built from server/)"
command -v curl >/dev/null || die "curl not found"
ensure_docker

if curl -sf -o /dev/null "http://127.0.0.1:$API_PORT/api/v1/health/live"; then
    if [ -f "$STATE_DIR/server.pid" ] && kill -0 "$(cat "$STATE_DIR/server.pid")" 2>/dev/null; then
        "$E2E_DIR/stack-down.sh" >/dev/null
    else
        die "port $API_PORT is taken by another server; stop it or set SHROUD_E2E_API_PORT"
    fi
fi

docker rm -f "$PREFIX-pg" "$PREFIX-redis" "$PREFIX-ntfy" >/dev/null 2>&1 || true
docker run -d --rm --name "$PREFIX-pg" \
    -e POSTGRES_USER=shroud -e POSTGRES_PASSWORD=shroud -e POSTGRES_DB=shroud_android \
    -p "127.0.0.1:$PG_PORT:5432" postgres:16-alpine >/dev/null
docker run -d --rm --name "$PREFIX-redis" -p "127.0.0.1:$REDIS_PORT:6379" redis:7-alpine >/dev/null
docker run -d --rm --name "$PREFIX-ntfy" \
    -e "NTFY_BASE_URL=http://localhost:$NTFY_PORT" -e "NTFY_LISTEN_HTTP=:$NTFY_PORT" \
    -e NTFY_CACHE_DURATION=12h \
    -p "127.0.0.1:$NTFY_PORT:$NTFY_PORT" binwiederhier/ntfy serve >/dev/null

echo "building the API…"
(cd "$REPO_ROOT/server" && cargo build -q -p shroud-server)
[ -x "$SERVER_BIN" ] || die "no server binary at $SERVER_BIN (CARGO_TARGET_DIR?)"

ready=0
for i in $(seq 1 30); do
    if docker exec "$PREFIX-pg" pg_isready -q -U shroud -d shroud_android 2>/dev/null; then
        ready=1
        break
    fi
    sleep 1
done
[ "$ready" = 1 ] || die "Postgres did not come up"

mkdir -p "$STATE_DIR/media"
# A clean environment: nothing from the developer's shell (APNs keys, a production DATABASE_URL)
# leaks into the throwaway server. UNIFIEDPUSH_* lets the server push to the local ntfy over
# plain HTTP (X1-SRV-UP; an older server ignores them).
env -i PATH="$PATH" HOME="$HOME" \
    DATABASE_URL="postgres://shroud:shroud@127.0.0.1:$PG_PORT/shroud_android" \
    REDIS_URL="redis://127.0.0.1:$REDIS_PORT" \
    RUN_MIGRATIONS=true HOST=127.0.0.1 PORT="$API_PORT" MEDIA_DATA_DIR="$STATE_DIR/media" \
    UNIFIEDPUSH_ALLOW_LOCAL_HTTP=true UNIFIEDPUSH_ALLOWED_HOSTS=localhost \
    RUST_LOG=info nohup "$SERVER_BIN" >"$STATE_DIR/server.log" 2>&1 &
echo $! >"$STATE_DIR/server.pid"

wait_for_url "http://127.0.0.1:$NTFY_PORT/v1/health" 30 || die "ntfy did not come up"
if wait_for_url "http://127.0.0.1:$API_PORT/api/v1/health/ready" 60; then
    echo "stack up: API http://127.0.0.1:$API_PORT/api/v1 (emulator: http://10.0.2.2:$API_PORT/api/v1), ntfy http://localhost:$NTFY_PORT"
    echo "server log: $STATE_DIR/server.log"
else
    tail -20 "$STATE_DIR/server.log" >&2
    die "the API did not come up"
fi
