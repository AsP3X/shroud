#!/usr/bin/env bash
# Prepares one emulator (or test phone) for e2e runs (00-plan §6.3): emulator-setup.sh [serial]
#   1. a screen lock — PIN 1234 — because the history-key vault needs one; the screen then stays
#      on, so it does not sleep and relock (after a cold boot, unlock with unlock.sh);
#   2. Android 17+: ACCESS_LOCAL_NETWORK for the app, without which traffic to 10.0.2.2 or a LAN
#      server just times out (install the app first);
#   3. adb reverse tcp:2586, so http://localhost:2586 (the stack's ntfy) is one URL for the
#      emulator's distributor and the server on the host.
# Idempotent: run it again after a reinstall or a cold boot.
set -euo pipefail
. "$(dirname "$0")/common.sh"

SERIAL="${1:-${SERIAL:-}}"
PIN="${SHROUD_E2E_PIN:-1234}"

adb_cmd get-state >/dev/null 2>&1 || die "no device${SERIAL:+ $SERIAL} (adb devices)"

# 1. Screen lock.
set_out="$(adb_cmd shell locksettings set-pin "$PIN" 2>&1 || true)"
if echo "$set_out" | grep -qi "set to"; then
    echo "screen lock: PIN $PIN set"
elif adb_cmd shell locksettings verify --old "$PIN" 2>&1 | grep -qi "verified successfully"; then
    echo "screen lock: PIN $PIN already set"
else
    die "a different screen lock is set; remove it or set SHROUD_E2E_PIN ($set_out)"
fi
adb_cmd shell svc power stayon true
"$E2E_DIR/unlock.sh" "$SERIAL"

# 2. Local-network permission (API 37, Android 17).
sdk="$(adb_cmd shell getprop ro.build.version.sdk | tr -d '\r')"
if [ "${sdk:-0}" -ge 37 ]; then
    if adb_cmd shell pm list packages "$APP_ID" | tr -d '\r' | grep -qx "package:$APP_ID"; then
        adb_cmd shell pm grant "$APP_ID" android.permission.ACCESS_LOCAL_NETWORK
        echo "ACCESS_LOCAL_NETWORK granted to $APP_ID"
    else
        echo "warning: $APP_ID is not installed; install it and run this again for ACCESS_LOCAL_NETWORK" >&2
    fi
fi

# 3. The local ntfy on the emulator's localhost.
adb_cmd reverse "tcp:$NTFY_PORT" "tcp:$NTFY_PORT" >/dev/null
echo "adb reverse tcp:$NTFY_PORT → host ntfy"
