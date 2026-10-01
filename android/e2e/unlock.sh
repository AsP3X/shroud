#!/usr/bin/env bash
# Wakes the device and, only if the lock screen is in front, enters the e2e PIN: unlock.sh [serial]
# (Typing the PIN blindly would land in whatever field has focus when the phone is unlocked.)
set -euo pipefail
. "$(dirname "$0")/common.sh"

SERIAL="${1:-${SERIAL:-}}"
PIN="${SHROUD_E2E_PIN:-1234}"

focus() {
    adb_cmd shell dumpsys window | tr -d '\r' | grep -m1 'mCurrentFocus' || true
}

locked() {
    case "$(focus)" in
        *NotificationShade* | *Keyguard* | *Bouncer* | *StatusBar*) return 0 ;;
        *) return 1 ;;
    esac
}

adb_cmd shell input keyevent KEYCODE_WAKEUP
sleep 0.5
if ! locked; then
    echo "unlocked"
    exit 0
fi
# Right after a cold boot the keyguard can still be settling: the first PIN entry is then lost
# and the phone stays locked, which leaves Keystore refusing every auth-bound key ("Secure lock
# screen must be enabled…"). Try a few times, waiting for the bouncer between attempts.
for attempt in 1 2 3 4; do
    adb_cmd shell wm dismiss-keyguard
    sleep 1
    adb_cmd shell input text "$PIN"
    adb_cmd shell input keyevent KEYCODE_ENTER
    sleep $((attempt + 1))
    if ! locked; then
        echo "unlocked with the PIN${attempt:+ (attempt $attempt)}"
        exit 0
    fi
    adb_cmd shell input keyevent KEYCODE_WAKEUP
done
die "still locked after 4 attempts (is the PIN $PIN?)"
