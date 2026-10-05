#!/usr/bin/env bash
# The wave 2 engine e2e (00-plan §2.3 W2-INT, §6.3): engine-e2e.sh [serial] [test method]
#   1. bundles the scripted web peer (peer/peer.ts) with the web's own esbuild (needs `npm ci` in
#      web/) and starts $PEER_COUNT of them (one web account each: the web vault is one per page) on
#      127.0.0.1:$PEER_PORT… — the emulator reaches them as 10.0.2.2:$PEER_PORT…;
#   2. runs each test of androidTest EngineE2eTest (or the one named) against the stack and the peers;
#   3. stops the peers.
# Needs stack-up.sh (the API) and emulator-setup.sh (screen lock, local-network grant) first; the
# app and test APKs are installed by Gradle. The emulator should run with `-feature -HardwareDecoder`
# (README: the video step encodes with the software codecs).
set -euo pipefail
. "$(dirname "$0")/common.sh"

SERIAL="${1:-${SERIAL:-}}"
TEST_METHOD="${2:-}"
PEER_PORT="${SHROUD_E2E_PEER_PORT:-8099}"
# EngineE2eTest: the web peer, a peer added by share code, one added by link, and this phone's other device.
PEER_COUNT="${SHROUD_E2E_PEER_COUNT:-4}"
ESBUILD="$REPO_ROOT/web/node_modules/.bin/esbuild"
[ -x "$ESBUILD" ] || die "no esbuild at $ESBUILD: run npm ci in web/"
command -v node >/dev/null || die "node not found"
curl -sf -o /dev/null "http://127.0.0.1:$API_PORT/api/v1/health/live" || die "the API is not up: run stack-up.sh"

mkdir -p "$STATE_DIR/peer"
"$ESBUILD" "$E2E_DIR/peer/peer.ts" --bundle --platform=node --format=esm --target=node22 \
    --log-level=warning --outfile="$STATE_DIR/peer/peer.mjs"
PEER_PIDS=()
trap 'for pid in "${PEER_PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done' EXIT
for i in $(seq 0 $((PEER_COUNT - 1))); do
    port=$((PEER_PORT + i))
    node "$STATE_DIR/peer/peer.mjs" --api "http://127.0.0.1:$API_PORT/api/v1" --port "$port" \
        --photo "$REPO_ROOT/android/app/src/test/resources/media/imageio/tagged.jpg" >"$STATE_DIR/peer-$i.log" 2>&1 &
    PEER_PIDS+=($!)
done
for i in $(seq 0 $((PEER_COUNT - 1))); do
    wait_for_url "http://127.0.0.1:$((PEER_PORT + i))/health" 15 || { cat "$STATE_DIR/peer-$i.log" >&2; die "peer $i did not come up"; }
done

# connectedAndroidTest runs on every attached device unless ANDROID_SERIAL names one.
if [ -z "$SERIAL" ] && [ "$(adb_cmd devices | tr -d '\r' | grep -c $'\tdevice$' || true)" -gt 1 ]; then
    die "several devices attached: pass the emulator's serial (engine-e2e.sh emulator-5556)"
fi
[ -n "$SERIAL" ] && export ANDROID_SERIAL="$SERIAL"

cd "$REPO_ROOT/android"
GRADLE="${GRADLE:-./gradlew}"
# One method at a time, with the rate-limit windows cleared before each: the stack allows 10 auth
# requests a minute for every local client together, and the class signs up a dozen accounts.
METHODS=(theEnginesExchangeEveryKindWithTheWebPeer
    filesTravelBothWaysWithTheWebPeer
    aSecondDeviceSharesReadsMutesNotesAndDeletesConflictsAReactionAndRevokesThisPhone
    chatDeletesFollowThePeersConsent
    contactsComeByShareCodeLinkAndNameWithPresenceBlocksAndKeyChanges
    callsRingConnectAndHangUpBothWays)
[ -n "$TEST_METHOD" ] && METHODS=("$TEST_METHOD")
for method in "${METHODS[@]}"; do
    "$E2E_DIR/reset-limits.sh" >/dev/null
    echo "engine e2e: $method"
    "$GRADLE" :app:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class="de.corespace.shroud.e2e.EngineE2eTest#$method" \
        -Pandroid.testInstrumentationRunnerArguments.shroudApi="http://10.0.2.2:$API_PORT/api/v1" \
        -Pandroid.testInstrumentationRunnerArguments.shroudPeer="http://10.0.2.2:$PEER_PORT" \
        -Pandroid.testInstrumentationRunnerArguments.shroudPeerCount="$PEER_COUNT" \
        -Pandroid.testInstrumentationRunnerArguments.shroudRequired=true
done
echo "engine e2e passed (${#METHODS[@]} tests); peer logs: $STATE_DIR/peer-*.log"
