#!/usr/bin/env bash
# Types a 12-word phrase into the Log In phrase grid: enter_phrase.sh "<word1 … word12>"
# Each field is cleared first; Enter moves to the next one. Uses ui.py (SERIAL picks the device).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
[ $# -eq 1 ] || { echo "usage: $0 \"<12 words>\"" >&2; exit 2; }

"$HERE/ui.py" tap "Word 1" >/dev/null
for word in $1; do
    "$HERE/ui.py" del 12
    "$HERE/ui.py" type "$word"
    "$HERE/ui.py" key KEYCODE_ENTER
    sleep 0.4
done
