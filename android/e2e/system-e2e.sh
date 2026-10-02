#!/usr/bin/env bash
# Wave 3 system e2e (G8). One serial, one pass:
#   in-process SystemE2eTest, then a force-stopped app: push, read, Doze, standby,
#   reboot, and removal confirmed with GET /auth/me.
# A killed process does not ring (G9 attaches the call system). That miss is reported
# and is not a failure.
#
# Needs stack-up.sh first. Does not start or stop the stack.
#   SHROUD_E2E_PREFIX=shroud-g8 SHROUD_E2E_STATE=/tmp/shroud-g8-e2e \
#   SHROUD_E2E_API_PORT=18080 SHROUD_E2E_NTFY_PORT=2587 \
#   android/e2e/system-e2e.sh emulator-5560
# SHROUD_E2E_SKIP_BUILD=1 skips Gradle when the APKs are already built.
set -euo pipefail
. "$(dirname "$0")/common.sh"

SERIAL="${1:-${SERIAL:-}}"
case "$SERIAL" in
    emulator-5560 | emulator-5562) ;;
    *) die "refusing serial '${SERIAL:-}' (only emulator-5560 and emulator-5562)" ;;
esac

PEER_PORT="${SHROUD_E2E_PEER_PORT:-18199}"
PEER_COUNT=2
ESBUILD="$REPO_ROOT/web/node_modules/.bin/esbuild"
APP_APK="$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$REPO_ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
STUB_APK="$REPO_ROOT/android/e2e/up-stub/build/outputs/apk/debug/up-stub-debug.apk"
STUB_ID="de.corespace.shroud.upstub"
IDLE=0
PEER_PIDS=()

mkdir -p "$STATE_DIR/runs" "$STATE_DIR/peer"
LOG="$STATE_DIR/runs/${SERIAL}-$(date +%Y%m%dT%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1
echo "system e2e log: $LOG"

cleanup() {
    if [ "$IDLE" = 1 ]; then
        adb_cmd shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
        IDLE=0
    fi
    local pid
    # bash 3.2 treats an empty "${array[@]}" as unbound under `set -u`.
    for pid in ${PEER_PIDS[@]+"${PEER_PIDS[@]}"}; do
        kill "$pid" 2>/dev/null || true
    done
}
trap cleanup EXIT

[ -x "$ESBUILD" ] || die "no esbuild at $ESBUILD: run npm ci in web/"
command -v node >/dev/null || die "node not found"
curl -sf -o /dev/null "http://127.0.0.1:$API_PORT/api/v1/health/live" || die "the API is not up: run stack-up.sh"
adb_cmd get-state >/dev/null 2>&1 || die "no device $SERIAL"

if [ "${SHROUD_E2E_SKIP_BUILD:-}" != 1 ]; then
    export JAVA_HOME="${JAVA_HOME:-/Users/nvorberg/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home}"
    (cd "$REPO_ROOT/android" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline)
    (cd "$REPO_ROOT/android" && ./gradlew -p e2e/up-stub assembleDebug --offline)
fi
[ -f "$APP_APK" ] && [ -f "$TEST_APK" ] && [ -f "$STUB_APK" ] || die "missing apk"

echo "installing"
adb_cmd install -r -t "$APP_APK" >/dev/null
adb_cmd install -r -t "$TEST_APK" >/dev/null
adb_cmd install -r -t "$STUB_APK" >/dev/null
adb_cmd shell pm clear "$APP_ID" >/dev/null
adb_cmd shell pm clear "$STUB_ID" >/dev/null

SERIAL="$SERIAL" "$E2E_DIR/emulator-setup.sh" "$SERIAL"

SDK="$(adb_cmd shell getprop ro.build.version.sdk | tr -d '\r')"
if [ "${SDK:-0}" -ge 33 ]; then
    adb_cmd shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS
    adb_cmd shell pm grant "$STUB_ID" android.permission.POST_NOTIFICATIONS
fi
adb_cmd shell pm grant "$APP_ID" android.permission.RECORD_AUDIO || true
if [ "${SDK:-0}" -ge 34 ]; then
    adb_cmd shell appops set "$APP_ID" USE_FULL_SCREEN_INTENT allow || true
fi
if adb_cmd shell pm path com.google.android.gms >/dev/null 2>&1; then
    adb_cmd shell pm disable-user --user 0 com.google.android.gms || true
fi

# A broadcast cannot start the stub's foreground service on API 31+. The activity can.
start_stub() {
    adb_cmd shell am broadcast -a de.corespace.shroud.upstub.CONFIG \
        -n "$STUB_ID/.DistributorReceiver" --ei port "$NTFY_PORT" >/dev/null || true
    adb_cmd shell am start -n "$STUB_ID/.StarterActivity" --ei port "$NTFY_PORT" >/dev/null
}
start_stub

"$ESBUILD" "$E2E_DIR/peer/peer.ts" --bundle --platform=node --format=esm --target=node22 \
    --log-level=warning --outfile="$STATE_DIR/peer/peer.mjs"
for i in $(seq 0 $((PEER_COUNT - 1))); do
    port=$((PEER_PORT + i))
    node "$STATE_DIR/peer/peer.mjs" --api "http://127.0.0.1:$API_PORT/api/v1" --port "$port" \
        >"$STATE_DIR/peer-$SERIAL-$i.log" 2>&1 &
    PEER_PIDS+=($!)
done
for i in $(seq 0 $((PEER_COUNT - 1))); do
    wait_for_url "http://127.0.0.1:$((PEER_PORT + i))/health" 20 || {
        cat "$STATE_DIR/peer-$SERIAL-$i.log" >&2
        die "peer $i did not come up"
    }
done
# Host health is not enough. Right after boot the API 30 emulator reached the API at
# 10.0.2.2 and never opened the peer port (the peer log showed only the host check).
# Wait until the device itself gets a peer health response.
for i in $(seq 0 $((PEER_COUNT - 1))); do
    port=$((PEER_PORT + i))
    ok=0
    for _ in $(seq 1 20); do
        # No grep -q: under pipefail its early exit is SIGPIPE and the check looks failed.
        if adb_cmd shell "printf 'GET /health HTTP/1.1\r\nHost: 10.0.2.2\r\nConnection: close\r\n\r\n' | nc -4 -w 3 10.0.2.2 $port" | grep '"ok":true' >/dev/null; then
            ok=1
            break
        fi
        sleep 1
    done
    [ "$ok" = 1 ] || die "emulator cannot reach peer $port at 10.0.2.2"
done

instrument() {
    local method=$1
    local out="$STATE_DIR/instrument-$method.txt"
    echo "instrument: $method"
    adb_cmd shell am instrument -w -r \
        -e class "de.corespace.shroud.e2e.SystemE2eTest#$method" \
        -e shroudApi "http://10.0.2.2:$API_PORT/api/v1" \
        -e shroudPeer "http://10.0.2.2:$PEER_PORT" \
        -e shroudPeerCount "$PEER_COUNT" \
        -e shroudRequired true \
        -e shroudNtfyPort "$NTFY_PORT" \
        de.corespace.shroud.test/androidx.test.runner.AndroidJUnitRunner >"$out" 2>&1 || true
    cat "$out"
    # AndroidJUnitRunner finishes a pass with Activity.RESULT_OK, which is -1.
    if grep -q 'FAILURES!!!' "$out" || grep -q 'Process crashed' "$out"; then
        die "instrument $method failed"
    fi
    grep -q 'OK (' "$out" || die "instrument $method did not report OK"
}

"$E2E_DIR/reset-limits.sh" >/dev/null
instrument aliveProcessPaths
"$E2E_DIR/reset-limits.sh" >/dev/null
instrument prepareClosedApp

peer_post() {
    local index=$1 path=$2 body=$3
    curl -sf -X POST -H 'content-type: application/json' --data "$body" \
        "http://127.0.0.1:$((PEER_PORT + index))$path" >/dev/null
}

echo "reading the prepared account from the peers"
eval "$(python3 - "$PEER_PORT" <<'PY'
import json, sys, urllib.request
base = int(sys.argv[1])
def get(port, path):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}") as response:
        return json.load(response)
# A new contact has no conversation until the first message. whoami is the signed-in account.
friend = get(base, "/whoami")
android_user = get(base + 1, "/whoami")
devices = get(base + 1, "/devices")["devices"]
android = next(d for d in devices if not d.get("current"))
print(f"ANDROID_ID={android_user['userId']}")
print(f"FRIEND_ID={friend['userId']}")
print(f"FRIEND_NAME={friend['username']}")
print(f"DEVICE_ID={android['id']}")
PY
)"
[ -n "${ANDROID_ID:-}" ] && [ -n "${FRIEND_NAME:-}" ] && [ -n "${DEVICE_ID:-}" ] || die "could not name the prepared account"

note_eval() {
    python3 - "$STATE_DIR/notif.txt" "$1" "$FRIEND_NAME" <<'PY'
import re, sys
path, mode, title = sys.argv[1:]
dump = open(path, errors="replace").read()
blocks = [b for b in dump.split("NotificationRecord(") if re.search(r"pkg=de\.corespace\.shroud\b", b)]
def texts(block):
    return re.findall(r"android\.text=String \((.*?)\)", block)
msgs = [b for b in blocks if "New message" in texts(b) and title in re.findall(r"android\.title=String \((.*?)\)", b)]
multi = [b for b in blocks if any(t.endswith(" new messages") for t in texts(b))]
connected = any("Connected to receive messages" in t for b in blocks for t in texts(b))
# dumpsys omits the activity class. The ring is channel calls.incoming. API 31+ text is
# "Incoming voice call"; the API 30 CallStyle fallback replaces it with "Incoming call".
incoming = [b for b in blocks if "channel=calls.incoming" in b and any(t in ("Incoming voice call", "Incoming call") for t in texts(b))]
missed = [b for b in blocks if "Call back" in b]
checks = {
    "named": len(msgs) == 1 and not multi,
    "none": len(msgs) == 0 and not multi,
    "connected": connected,
    "incoming": len(incoming) >= 1,
    "missed": len(missed) >= 1 and len(incoming) == 0,
}
ok = checks.get(mode, False)
print(f"msgs={len(msgs)} multi={len(multi)} connected={int(connected)} incoming={len(incoming)} missed={len(missed)}")
sys.exit(0 if ok else 1)
PY
}

wait_note() {
    local secs=$1 mode=$2
    local i line=""
    for i in $(seq 1 "$secs"); do
        adb_cmd shell dumpsys notification --noredact >"$STATE_DIR/notif.txt" || true
        if line="$(note_eval "$mode")"; then
            echo "notification $mode: $line"
            return 0
        fi
        sleep 1
    done
    echo "notification $mode timed out: $line" >&2
    return 1
}

force_stop() {
    adb_cmd shell am force-stop "$APP_ID"
}

send_text() {
    peer_post 0 /text "{\"peer\":\"$ANDROID_ID\",\"text\":\"x\"}"
}

echo "killed app: push posts a named notification"
force_stop
send_text
wait_note 60 named

echo "read push closes that chat"
peer_post 1 /read "{\"peer\":\"$FRIEND_ID\"}"
wait_note 45 none

echo "doze"
force_stop
adb_cmd shell dumpsys deviceidle force-idle >/dev/null
IDLE=1
send_text
wait_note 60 named
adb_cmd shell dumpsys deviceidle unforce >/dev/null || true
IDLE=0

echo "standby bucket rare"
adb_cmd shell am set-standby-bucket "$APP_ID" rare
force_stop
send_text
wait_note 60 named
# Push delivery does not clear the stopped state left by force-stop, so boot
# broadcasts would be dropped. The launcher activity runs the shell, which finishes
# a pending wipe and deletes the session. CallActivity only finishes, and the shell
# can start that non-exported activity once adbd is root. Do not force-stop again.
adb_cmd shell am set-standby-bucket "$APP_ID" active || true
adb_cmd root >/dev/null 2>&1 || die "adb root is required to clear the stopped state"
adb_cmd wait-for-device
adb_cmd shell am start -n "$APP_ID/.ui.calls.CallActivity" >/dev/null
cleared=0
for _ in $(seq 1 15); do
    if adb_cmd shell dumpsys package "$APP_ID" | grep -q 'stopped=false'; then
        cleared=1
        break
    fi
    sleep 1
done
[ "$cleared" = 1 ] || die "package stayed stopped; boot resume would be suppressed"
# dumpsys updates immediately. PackageManager writes the flag about 10s later, and a
# reboot before that write reloads stopped=true, so boot broadcasts never arrive.
# API 31+ stores Android Binary XML (abx2xml). API 30 stores text XML and has no abx2xml.
saved=0
for _ in $(seq 1 30); do
    magic=$(adb_cmd shell 'head -c 5 /data/system/users/0/package-restrictions.xml' 2>/dev/null | tr -d '\r')
    if [ "$magic" = "<?xml" ]; then
        xml_cmd="cat /data/system/users/0/package-restrictions.xml"
    else
        xml_cmd="abx2xml /data/system/users/0/package-restrictions.xml -"
    fi
    if adb_cmd shell "$xml_cmd" 2>/dev/null | python3 -c "import sys,re; t=sys.stdin.read(); m=re.search(r'<pkg name=\"de.corespace.shroud\"[^>]*>', t); tag=m.group(0) if m else ''; sys.exit(0 if tag and 'stopped=\"true\"' not in tag else 1)"; then
        saved=1
        break
    fi
    sleep 1
done
[ "$saved" = 1 ] || die "stopped flag was not saved; boot resume would be suppressed"

echo "reboot resume"
adb_cmd reboot
adb_cmd wait-for-device
booted=0
boot_at=0
for _ in $(seq 1 120); do
    if [ "$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        booted=1
        boot_at=$(date +%s)
        break
    fi
    sleep 2
done
[ "$booted" = 1 ] || die "reboot did not finish"
"$E2E_DIR/unlock.sh" "$SERIAL"
adb_cmd shell svc power stayon true || true
adb_cmd reverse "tcp:$NTFY_PORT" "tcp:$NTFY_PORT" >/dev/null
# A start inside the ~20s boot allowlist is still attributed to BOOT_COMPLETED.
elapsed=$(( $(date +%s) - boot_at ))
if [ "$elapsed" -lt 25 ]; then
    sleep $((25 - elapsed))
fi
start_stub
# Unlock sends USER_UNLOCKED. The shell cannot send BOOT_COMPLETED.
if ! wait_note 50 connected; then
    die "background connection did not resume after unlock"
fi
send_text
wait_note 60 named

echo "killed-app ring"
force_stop
peer_post 0 /socket '{"on":true}' || true
peer_post 0 /call/start "{\"peer\":\"$ANDROID_ID\"}" || true
if wait_note 25 incoming; then
    echo "killed-app ring: CallStyle posted"
else
    echo "BLOCKED on G9: killed-app ring did not post a CallStyle notification"
fi
peer_post 0 /call/hangup '{}' || true

echo "removal while closed, after GET /auth/me"
force_stop
MARK="$(wc -c <"$STATE_DIR/server.log" | tr -d ' ')"
peer_post 1 /revoke "{\"deviceId\":\"$DEVICE_ID\"}"
gone=0
for _ in $(seq 1 70); do
    if adb_cmd shell run-as "$APP_ID" ls no_backup >"$STATE_DIR/nb.txt" 2>"$STATE_DIR/nb.err"; then
        if ! grep -q 'session.sealed' "$STATE_DIR/nb.txt"; then
            gone=1
            break
        fi
    else
        echo "run-as failed" >&2
        cat "$STATE_DIR/nb.err" >&2 || true
        break
    fi
    sleep 1
done
[ "$gone" = 1 ] || die "session file still present after revoke"
if ! tail -c +$((MARK + 1)) "$STATE_DIR/server.log" | grep -q 'auth/me'; then
    die "session file was removed without GET /auth/me in the server log"
fi
echo "removal confirmed: session file gone after GET /auth/me"

echo "RESULT pass"
echo "system e2e log: $LOG"
