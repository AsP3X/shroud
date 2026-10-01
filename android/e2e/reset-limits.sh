#!/usr/bin/env bash
# Clears the rate-limit windows of the local stack (auth 10 a minute, WebSocket 30 a minute per
# address — compile-time limits, and every local client shares one bucket). Flushes the stack's
# Redis: run it between suites, not in the middle of a call.
set -euo pipefail
. "$(dirname "$0")/common.sh"

docker exec "$PREFIX-redis" redis-cli FLUSHDB >/dev/null || die "no $PREFIX-redis container (run stack-up.sh)"
echo "rate limits reset"
