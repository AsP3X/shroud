#!/usr/bin/env bash
# The wave 2 engine e2e (00-plan §2.3 W2-INT, §6.3): engine-e2e.sh [serial]
#   1. bundles the scripted web peer (peer/peer.ts) with the web's own esbuild (needs `npm ci` in
#      web/) and starts it on 127.0.0.1:$PEER_PORT — the emulator reaches it as 10.0.2.2:$PEER_PORT;
#   2. runs androidTest EngineE2eTest against the stack and the peer;
#   3. stops the peer.
# Needs stack-up.sh (the API) and emulator-setup.sh (screen lock, local-network grant) first; the
# app and test APKs are installed by Gradle. The emulator should run with `-feature -HardwareDecoder`
# (README: the video step encodes with the software codecs).
set -euo pipefail
. "$(dirname "$0")/common.sh"

SERIAL="${1:-${SERIAL:-}}"
PEER_PORT="${SHROUD_E2E_PEER_PORT:-8099}"
ESBUILD="$REPO_ROOT/web/node_modules/.bin/esbuild"
[ -x "$ESBUILD" ] || die "no esbuild at $ESBUILD: run npm ci in web/"
command -v node >/dev/null || die "node not found"
curl -sf -o /dev/null "http://127.0.0.1:$API_PORT/api/v1/health/live" || die "the API is not up: run stack-up.sh"

mkdir -p "$STATE_DIR/peer"
"$ESBUILD" "$E2E_DIR/peer/peer.ts" --bundle --platform=node --format=esm --target=node22 \
    --log-level=warning --outfile="$STATE_DIR/peer/peer.mjs"
node "$STATE_DIR/peer/peer.mjs" --api "http://127.0.0.1:$API_PORT/api/v1" --port "$PEER_PORT" \
    --photo "$REPO_ROOT/android/app/src/test/resources/media/imageio/tagged.jpg" >"$STATE_DIR/peer.log" 2>&1 &
PEER_PID=$!
trap 'kill $PEER_PID 2>/dev/null || true' EXIT
wait_for_url "http://127.0.0.1:$PEER_PORT/health" 15 || { cat "$STATE_DIR/peer.log" >&2; die "the peer did not come up"; }

# connectedAndroidTest runs on every attached device unless ANDROID_SERIAL names one.
if [ -z "$SERIAL" ] && [ "$(adb_cmd devices | tr -d '\r' | grep -c $'\tdevice$' || true)" -gt 1 ]; then
    die "several devices attached: pass the emulator's serial (engine-e2e.sh emulator-5556)"
fi
[ -n "$SERIAL" ] && export ANDROID_SERIAL="$SERIAL"

cd "$REPO_ROOT/android"
GRADLE="${GRADLE:-./gradlew}"
"$GRADLE" :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=de.corespace.shroud.e2e.EngineE2eTest \
    -Pandroid.testInstrumentationRunnerArguments.shroudApi="http://10.0.2.2:$API_PORT/api/v1" \
    -Pandroid.testInstrumentationRunnerArguments.shroudPeer="http://10.0.2.2:$PEER_PORT"
echo "engine e2e passed; peer log: $STATE_DIR/peer.log"
