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
adb_cmd shell wm dismiss-keyguard
sleep 1
adb_cmd shell input text "$PIN"
adb_cmd shell input keyevent KEYCODE_ENTER
sleep 1
if locked; then
    die "still locked (is the PIN $PIN?)"
fi
echo "unlocked with the PIN"
