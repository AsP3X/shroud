#!/usr/bin/env bash
# Cold-starts the app and prints the launch status and time: launch.sh [serial]
# Force-stops first: an `am start` right after `adb install -r` often lands on the launcher.
set -euo pipefail
. "$(dirname "$0")/common.sh"

SERIAL="${1:-${SERIAL:-}}"
adb_cmd shell am force-stop "$APP_ID"
sleep 1
adb_cmd shell am start -W -n "$APP_ID/.MainActivity" | grep -E "Status|TotalTime"
