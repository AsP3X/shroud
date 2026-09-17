#!/usr/bin/env bash
# One-command deploy for the Shroud API + web client.
# Does NOT require Make — Docker Compose v2 + bash 3.2 (stock macOS).
#
#   ./deploy.sh                  first-time setup or start / redeploy
#   ./deploy.sh --init           force the setup wizard
#   ./deploy.sh --status         URLs + container table
#   ./deploy.sh --ps             container table
#   ./deploy.sh --logs [svc...]  follow logs
#   ./deploy.sh --restart [svc]  restart
#   ./deploy.sh --rebuild        rebuild images, then start
#   ./deploy.sh --down           stop stack
#   ./deploy.sh --help

set -Eeuo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")" || {
  echo "ERROR: cannot enter the repository directory." >&2
  exit 1
}
REPO_ROOT="$(pwd)"

if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[0;31m'
  GREEN=$'\033[0;32m'; YELLOW=$'\033[0;33m'; CYAN=$'\033[0;36m'; NC=$'\033[0m'
else
  BOLD=''; DIM=''; RED=''; GREEN=''; YELLOW=''; CYAN=''; NC=''
fi

step() { printf '%s→ %s%s\n' "$CYAN" "$*" "$NC"; }
ok()   { printf '%s✓ %s%s\n' "$GREEN" "$*" "$NC"; }
warn() { printf '%sWARNING: %s%s\n' "$YELLOW" "$*" "$NC" >&2; }
die()  { printf '%sERROR: %s%s\n' "$RED" "$*" "$NC" >&2; exit 1; }

CURRENT_STAGE="startup"
on_error() {
  local rc=$? line="$1" cmd="$2" src="${3:-$0}"
  printf '\n%sFailed during: %s%s (exit %s)\n' "$RED$BOLD" "$CURRENT_STAGE" "$NC" "$rc" >&2
  printf '  Command: %s%s%s\n' "$DIM" "$cmd" "$NC" >&2
  printf '  At:      %s%s:%s%s\n' "$DIM" "${src#./}" "$line" "$NC" >&2
  printf '  Inspect:  %s./deploy.sh --ps%s\n' "$DIM" "$NC" >&2
  printf '  Logs:     %s./deploy.sh --logs%s\n' "$DIM" "$NC" >&2
  printf '  Retry:    %s./deploy.sh --down && ./deploy.sh%s\n' "$DIM" "$NC" >&2
  exit "$rc"
}
trap 'on_error "$LINENO" "$BASH_COMMAND" "${BASH_SOURCE[0]}"' ERR

show_help() {
  cat <<EOF

  ${BOLD}Shroud — API + web client (Docker)${NC}

  ${BOLD}Usage:${NC}
    ./deploy.sh                    First-time setup, or start / redeploy
    ./deploy.sh --init             Force the setup wizard again
    ./deploy.sh --status           Show URLs and container status
    ./deploy.sh --ps               Container table only
    ./deploy.sh --logs [svc...]    Follow logs (all, or named services)
    ./deploy.sh --restart [svc...] Restart all services, or the named ones
    ./deploy.sh --rebuild          Rebuild images, then start
    ./deploy.sh --down             Stop and remove all services
    ./deploy.sh --help             This help

  ${BOLD}Service names${NC} (for --logs / --restart):
    api  web  postgres  redis  nebular

  ${BOLD}After deploy:${NC}
    Web:   http://localhost:8081   (PROXY_MODE=local; --status shows the real URL)
    API:   http://localhost:8080/api/v1
    iOS:   point the app at the API URL from --status

  ${BOLD}Environment:${NC}
    PROXY_MODE    local | npm   (from .env; npm joins proxy-network)
    NO_COLOR      disable coloured output

EOF
}

require_docker() {
  command -v docker >/dev/null 2>&1 ||
    die "Docker is not installed or not on PATH. See https://docs.docker.com/engine/install/"
  docker compose version >/dev/null 2>&1 ||
    die "Docker Compose v2 is required (the 'docker compose' subcommand, not 'docker-compose')."
  local err detail
  if ! err="$(docker info --format '{{.ServerVersion}}' 2>&1)"; then
    detail="$(printf '%s\n' "$err" | grep -v '^[[:space:]]*$' | tail -1 || true)"
    if grep -qi 'permission denied' <<<"$err"; then
      printf '%sERROR: cannot talk to the Docker daemon (permission denied).%s\n' "$RED" "$NC" >&2
      printf '  Add yourself to the docker group, then log out and back in:\n' >&2
      printf '    %ssudo usermod -aG docker "$USER"%s\n' "$DIM" "$NC" >&2
      exit 1
    fi
    printf '%sERROR: the Docker daemon is not reachable.%s\n' "$RED" "$NC" >&2
    printf '  Start it first — Docker Desktop, or: %ssudo systemctl start docker%s\n' "$DIM" "$NC" >&2
    printf '  Docker said: %s%s%s\n' "$DIM" "$detail" "$NC" >&2
    exit 1
  fi
}

require_compose_files() {
  local missing=() f
  for f in docker-compose.yml docker-compose.local.yml docker-compose.npm.yml scripts/compose-env.sh scripts/setup.sh web/Dockerfile server/Dockerfile; do
    [[ -f "$f" ]] || missing+=("$f")
  done
  if (( ${#missing[@]} )); then
    die "incomplete checkout — missing: ${missing[*]}
  Run ./deploy.sh from the repository root (currently: $REPO_ROOT)."
  fi
}

run_wizard() {
  if [[ ! -t 0 && "${SHROUD_SETUP_ASSUME_YES:-}" != "1" ]]; then
    die "the setup wizard needs an interactive terminal.
  Run ./deploy.sh --init from a terminal, or copy .env.example to .env and re-run."
  fi
  bash scripts/setup.sh
}

CMD=""
PASSTHRU=()
set_cmd() {
  [[ -z "$CMD" || "$CMD" == "$1" ]] ||
    die "only one command at a time (got '$CMD' and '$1'). See ./deploy.sh --help"
  CMD="$1"
}

while (( $# )); do
  case "$1" in
    -h|--help|help)          set_cmd help ;;
    --status|status)         set_cmd status ;;
    --ps|ps)                 set_cmd ps ;;
    --logs|logs)             set_cmd logs ;;
    --restart|restart)       set_cmd restart ;;
    --rebuild|rebuild)       set_cmd rebuild ;;
    --down|down|--stop|stop) set_cmd down ;;
    --init|init|--setup|setup) set_cmd init ;;
    --up|up)                 set_cmd up ;;
    --yes|-y)                export SHROUD_SETUP_ASSUME_YES=1 ;;
    --)                      shift; PASSTHRU+=("$@"); break ;;
    -*)                      die "unknown option: $1
  Valid: --init --status --ps --logs --restart --rebuild --down --help" ;;
    *)                       PASSTHRU+=("$1") ;;
  esac
  shift
done
CMD="${CMD:-up}"

case "$CMD" in
  logs|restart) ;;
  help) show_help; exit 0 ;;
  *)
    if (( ${#PASSTHRU[@]} )); then
      die "unexpected argument: ${PASSTHRU[*]}
  Only --logs and --restart accept service names. See ./deploy.sh --help"
    fi
    ;;
esac

CURRENT_STAGE="preflight"
require_compose_files
require_docker
# shellcheck disable=SC1091
source scripts/compose-env.sh

CURRENT_STAGE="$CMD"
case "$CMD" in
  status) shroud_info; exit 0 ;;
  ps)     shroud_compose ps; exit 0 ;;
  logs)
    step "Following logs (Ctrl-C to stop)…"
    trap - ERR
    shroud_compose logs -f --tail 200 ${PASSTHRU[@]+"${PASSTHRU[@]}"}
    exit 0
    ;;
  restart)
    step "Restarting ${PASSTHRU[*]:-all services}…"
    shroud_compose restart ${PASSTHRU[@]+"${PASSTHRU[@]}"}
    shroud_info
    exit 0
    ;;
  down) shroud_down; exit 0 ;;
  init) run_wizard; exit 0 ;;
esac

echo ""
echo "===================================================="
echo "  ${BOLD}Shroud — API + web client${NC}"
echo "===================================================="
echo ""

if [[ ! -f .env ]]; then
  CURRENT_STAGE="setup wizard"
  run_wizard
  exit 0
fi

STARTED_AT=$SECONDS
echo "Environment found — starting / redeploying stack..."
echo ""

CURRENT_STAGE="stack build/start"
if [[ "$CMD" == "rebuild" ]]; then
  step "Rebuilding images (--pull)…"
  shroud_compose build --pull
fi
shroud_up

ELAPSED=$(( SECONDS - STARTED_AT ))
echo ""
ok "Deploy finished in $(( ELAPSED / 60 ))m $(( ELAPSED % 60 ))s."
shroud_info
