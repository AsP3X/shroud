#!/usr/bin/env bash
# The server half of the wave 1 push gate (00-plan §2.2 W1-INT, §6.5): runs X1-SRV-UP's ignored
# end-to-end test against the stack of stack-up.sh — a call ring sealed per RFC 8291, sent to the
# stack's ntfy with `Urgency: high` and the ring TTL, arriving within a second. Uses the stack's
# Postgres (the test applies the migrations itself) and its ntfy; start the stack first.
set -euo pipefail
. "$(dirname "$0")/common.sh"

command -v cargo >/dev/null || die "cargo not found (the test is built from server/)"
wait_for_url "http://127.0.0.1:$NTFY_PORT/v1/health" 5 || die "no ntfy on port $NTFY_PORT (android/e2e/stack-up.sh)"

cd "$REPO_ROOT/server"
NTFY_URL="http://localhost:$NTFY_PORT" \
DATABASE_URL="postgres://shroud:shroud@127.0.0.1:$PG_PORT/shroud_android" \
REDIS_URL="redis://127.0.0.1:$REDIS_PORT" \
    cargo test -q -p shroud-server --test api_push -- --ignored --exact a_ring_reaches_a_local_ntfy_within_a_second --nocapture
