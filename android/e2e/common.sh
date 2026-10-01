# Shared settings of the Android e2e scripts (00-plan §6.3). Sourced, not run.
# Every value can be overridden from the environment; nothing here is specific to one machine.

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$E2E_DIR/../.." && pwd)"

# Server log, pid file and media store of the throwaway stack: outside the repo.
E2E_TMP="${TMPDIR:-/tmp}"
STATE_DIR="${SHROUD_E2E_STATE:-${E2E_TMP%/}/shroud-android-e2e}"
# Container names: <prefix>-pg, <prefix>-redis, <prefix>-ntfy.
PREFIX="${SHROUD_E2E_PREFIX:-shroud-android}"
PG_PORT="${SHROUD_E2E_PG_PORT:-55471}"
REDIS_PORT="${SHROUD_E2E_REDIS_PORT:-56391}"
# ntfy is the UnifiedPush push service; `adb reverse` makes http://localhost:<port> the same URL on
# the emulator (its distributor) and on the host (the server), 00-plan §6.5.
NTFY_PORT="${SHROUD_E2E_NTFY_PORT:-2586}"
# Debug builds talk to http://10.0.2.2:8080/api/v1 — the host's loopback as the emulator sees it.
API_PORT="${SHROUD_E2E_API_PORT:-8080}"
APP_ID="${SHROUD_APP_ID:-de.corespace.shroud}"
CARGO_TARGET="${CARGO_TARGET_DIR:-$REPO_ROOT/server/target}"
SERVER_BIN="$CARGO_TARGET/debug/shroud-server"

die() {
    echo "e2e: $*" >&2
    exit 1
}

# adb from $ADB, the SDK (ANDROID_HOME, ANDROID_SDK_ROOT, the default SDK folders) or PATH.
resolve_adb() {
    local candidate
    for candidate in "${ADB:-}" \
        "${ANDROID_HOME:+$ANDROID_HOME/platform-tools/adb}" \
        "${ANDROID_SDK_ROOT:+$ANDROID_SDK_ROOT/platform-tools/adb}" \
        "$HOME/Library/Android/sdk/platform-tools/adb" \
        "$HOME/Android/Sdk/platform-tools/adb"; do
        if [ -n "$candidate" ] && [ -x "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done
    command -v adb || die "adb not found: set ADB or ANDROID_HOME"
}

# adb against $SERIAL when set (several devices), else the only one attached.
adb_cmd() {
    if [ -n "${SERIAL:-}" ]; then
        "$(resolve_adb)" -s "$SERIAL" "$@"
    else
        "$(resolve_adb)" "$@"
    fi
}

# Docker running; on macOS Docker Desktop is started and awaited.
ensure_docker() {
    docker info >/dev/null 2>&1 && return 0
    if [ "$(uname)" = "Darwin" ]; then
        open -a Docker || die "Docker Desktop is not installed"
        local i
        for i in $(seq 1 60); do
            docker info >/dev/null 2>&1 && return 0
            sleep 2
        done
    fi
    die "Docker is not running"
}

# Polls a URL until it answers 2xx: wait_for_url <url> <seconds>.
wait_for_url() {
    local i
    for i in $(seq 1 "$2"); do
        curl -sf -o /dev/null "$1" && return 0
        sleep 1
    done
    return 1
}
