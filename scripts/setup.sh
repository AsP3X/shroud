#!/usr/bin/env bash
# First-time setup wizard — writes .env. Bash 3.2 compatible (stock macOS).
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"
# shellcheck disable=SC1091
source scripts/compose-env.sh

if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[0;31m'
  GREEN=$'\033[0;32m'; YELLOW=$'\033[0;33m'; CYAN=$'\033[0;36m'; NC=$'\033[0m'
else
  BOLD=''; DIM=''; RED=''; GREEN=''; YELLOW=''; CYAN=''; NC=''
fi

die() { printf '%sERROR: %s%s\n' "$RED" "$*" "$NC" >&2; exit 1; }

prompt() {
  local label="$1" default="$2" value=""
  if [[ -n "$default" ]]; then
    printf '  %s %s[%s]%s: ' "$label" "$DIM" "$default" "$NC" >&2
  else
    printf '  %s: ' "$label" >&2
  fi
  if [[ "${SHROUD_SETUP_ASSUME_YES:-}" == "1" ]]; then
    echo "" >&2
    value=""
  else
    read -r value || true
  fi
  printf '%s' "${value:-$default}"
}

generate_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$1"
  else
    dd if=/dev/urandom bs=1 count="$1" 2>/dev/null | od -An -tx1 | tr -d ' \n'
  fi
}

generate_secret() {
  generate_hex 32
}

if [[ -f .env && "${SHROUD_SETUP_ASSUME_YES:-}" != "1" ]]; then
  echo ""
  echo "${YELLOW}Existing .env detected.${NC}"
  printf '  Overwrite and reconfigure? %s[y/N]%s: ' "$DIM" "$NC"
  read -r overwrite || true
  case "$(printf '%s' "$overwrite" | tr '[:upper:]' '[:lower:]')" in
    y|yes) ;;
    *) echo "Cancelled."; exit 0 ;;
  esac
fi

echo ""
echo "${CYAN}${BOLD}══════════════════════════════════════════════${NC}"
echo "${CYAN}${BOLD}  Shroud — first-time setup${NC}"
echo "${CYAN}${BOLD}══════════════════════════════════════════════${NC}"
echo ""

echo "${BOLD}── How will you reach the stack? ──${NC}"
echo "  1) Local ports     — web :8081, API :8080 (this machine / iOS Simulator)"
echo "  2) Nginx Proxy Manager — join proxy-network, you map hostnames in NPM"
printf '  %s[1]%s: ' "$DIM" "$NC"
if [[ "${SHROUD_SETUP_ASSUME_YES:-}" == "1" ]]; then
  mode_choice="1"
  echo "1"
else
  read -r mode_choice || true
  mode_choice="${mode_choice:-1}"
fi

if [[ "$mode_choice" == "2" ]]; then
  PROXY_MODE="npm"
  WEB_PUBLIC_URL="$(prompt "Public web URL" "https://web.example.com")"
  API_PUBLIC_URL="$(prompt "Public API URL (iOS)" "https://api.example.com")"
  WEB_PORT="8081"
  API_PORT="8080"
else
  PROXY_MODE="local"
  WEB_PORT="$(prompt "Web host port" "8081")"
  API_PORT="$(prompt "API host port" "8080")"
  WEB_PUBLIC_URL="http://localhost:${WEB_PORT}"
  API_PUBLIC_URL="http://localhost:${API_PORT}"
fi

echo ""
echo "${BOLD}── Data storage ──${NC}"
echo "  Postgres, Nebular and media data live in named Docker volumes, or in a folder you can"
echo "  see and back up (database/, nebular/ and media/ inside it)."
EXISTING_DATA_DIR="$(shroud_env_value SHROUD_DATA_DIR)"
if [[ -n "${SHROUD_SETUP_DATA_DIR+set}" ]]; then
  # Chosen with ./deploy.sh --data-dir or --named-volumes.
  SHROUD_DATA_DIR_VALUE="$SHROUD_SETUP_DATA_DIR"
else
  # A first setup defaults to named volumes; a re-run keeps what .env has.
  if [[ -n "$EXISTING_DATA_DIR" ]]; then
    data_dir_default="y"; data_dir_hint="[Y/n]"
  else
    data_dir_default="n"; data_dir_hint="[y/N]"
  fi
  printf '  Store data in a folder instead of Docker volumes? %s%s%s: ' "$DIM" "$data_dir_hint" "$NC"
  data_dir_choice=""
  if [[ "${SHROUD_SETUP_ASSUME_YES:-}" == "1" ]]; then
    echo "$data_dir_default"
  else
    read -r data_dir_choice || true
  fi
  SHROUD_DATA_DIR_VALUE=""
  case "$(printf '%s' "${data_dir_choice:-$data_dir_default}" | tr '[:upper:]' '[:lower:]')" in
    y|yes)
      while :; do
        SHROUD_DATA_DIR_VALUE="$(prompt "Data folder (relative to this repository, or absolute)" "${EXISTING_DATA_DIR:-./data}")"
        # .env holds it unquoted, and Compose would interpolate a $ in it.
        case "$SHROUD_DATA_DIR_VALUE" in
          *[\$\"\'\\\#]*) echo "  ${RED}It can't contain \$ \" ' \\ or #.${NC}" >&2 ;;
          *) break ;;
        esac
        [[ "${SHROUD_SETUP_ASSUME_YES:-}" != "1" ]] || die "invalid data folder: $SHROUD_DATA_DIR_VALUE"
      done
      ;;
  esac
fi
if [[ -n "$SHROUD_DATA_DIR_VALUE" ]]; then
  switch_cmd="${SHROUD_SETUP_SWITCH_CMD:-./deploy.sh --data-dir $(printf '%q' "$SHROUD_DATA_DIR_VALUE")}"
else
  switch_cmd="${SHROUD_SETUP_SWITCH_CMD:-./deploy.sh --named-volumes}"
fi
# Leaving data behind on the other side stops here, before .env changes.
shroud_prepare_storage_switch "$SHROUD_DATA_DIR_VALUE" "$switch_cmd" || exit 1
DATA_DIR_RESOLVED="$(shroud_resolve_data_dir "$SHROUD_DATA_DIR_VALUE")"
STORAGE_LABEL="$(shroud_storage_label "$DATA_DIR_RESOLVED")"
echo "  Storage: ${GREEN}${STORAGE_LABEL}${NC}"

echo ""
echo "${BOLD}── Secrets ──${NC}"
# Postgres only hashes POSTGRES_PASSWORD on first volume init. Reuse the existing
# password whenever .env already has one so a wizard re-run cannot lock the API out.
EXISTING_PG=""
if [[ -f .env ]]; then
  EXISTING_PG="$(grep -E '^POSTGRES_PASSWORD=' .env 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r')"
fi
if [[ -n "$EXISTING_PG" && "$EXISTING_PG" != "GENERATE_ME" ]]; then
  POSTGRES_PASSWORD="$EXISTING_PG"
  echo "  Postgres password: ${GREEN}reused from .env${NC} (data already initialized)"
else
  POSTGRES_PASSWORD="$(generate_secret)"
  echo "  Postgres password: ${GREEN}generated${NC}"
  if shroud_pg_data_exists "$DATA_DIR_RESOLVED"; then
    echo "${YELLOW}  Postgres already has data in ${STORAGE_LABEL}. The new password will not apply to it.${NC}"
    echo "  Wipe it first: ${BOLD}./deploy.sh --down --volumes${NC}"
  fi
fi
NOS_JWT_SECRET="$(generate_secret)"
NEBULAR_ACCESS_KEY_ID="SHRD$(generate_hex 8 | tr '[:lower:]' '[:upper:]')"
NEBULAR_SECRET_ACCESS_KEY="$(generate_secret)"
NOS_METRICS_TOKEN="$(generate_secret)"
REDIS_PASSWORD="$(generate_secret)"
echo "  Nebular OS secret and the API's access key: ${GREEN}generated${NC}"
# Reused like the Postgres password: a new one only ends the TURN logins already handed out,
# but there is no reason to.
EXISTING_TURN=""
if [[ -f .env ]]; then
  EXISTING_TURN="$(grep -E '^TURN_SECRET=' .env 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r')"
fi
if [[ -n "$EXISTING_TURN" && "$EXISTING_TURN" != "GENERATE_ME" ]]; then
  TURN_SECRET="$EXISTING_TURN"
else
  TURN_SECRET="$(generate_secret)"
fi

echo ""
echo "${BOLD}── Calls ──${NC}"
echo "  Calls between networks that block direct connections (many mobile carriers) need the"
echo "  TURN relay (coturn) on this server. It needs UDP/TCP ${BOLD}3478${NC} and UDP ${BOLD}49160-49259${NC} open,"
echo "  reachable at a public hostname or IP. Leave it blank to go without."
if [[ "$PROXY_MODE" == "npm" ]]; then
  TURN_HOST_DEFAULT="$(printf '%s' "$API_PUBLIC_URL" | sed -E 's#^[A-Za-z]+://##; s#[:/].*$##')"
else
  TURN_HOST_DEFAULT=""
fi
TURN_HOST="$(prompt "Relay hostname or IP" "$TURN_HOST_DEFAULT")"
if [[ -n "$TURN_HOST" ]]; then
  TURN_URLS="turn:${TURN_HOST}:3478?transport=udp,turn:${TURN_HOST}:3478?transport=tcp"
  COMPOSE_PROFILES="calls"
  echo "  TURN relay: ${GREEN}on${NC} at ${TURN_HOST}"
else
  TURN_URLS=""
  COMPOSE_PROFILES=""
  echo "  TURN relay: ${DIM}off${NC}"
fi

CORS_ALLOWED_ORIGINS="$WEB_PUBLIC_URL"

umask 077
cat > .env <<EOF
PROXY_MODE=${PROXY_MODE}
WEB_PUBLIC_URL=${WEB_PUBLIC_URL}
API_PUBLIC_URL=${API_PUBLIC_URL}
WEB_PORT=${WEB_PORT}
API_PORT=${API_PORT}
CORS_ALLOWED_ORIGINS=${CORS_ALLOWED_ORIGINS}
POSTGRES_USER=shroud
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
POSTGRES_DB=shroud
NOS_JWT_SECRET=${NOS_JWT_SECRET}
NEBULAR_ACCESS_KEY_ID=${NEBULAR_ACCESS_KEY_ID}
NEBULAR_SECRET_ACCESS_KEY=${NEBULAR_SECRET_ACCESS_KEY}
NOS_METRICS_TOKEN=${NOS_METRICS_TOKEN}
REDIS_PASSWORD=${REDIS_PASSWORD}
COMPOSE_PROFILES=${COMPOSE_PROFILES}
TURN_URLS=${TURN_URLS}
TURN_SECRET=${TURN_SECRET}
RUST_LOG=info
RUST_LOG_FORMAT=text
EOF
if [[ -n "$SHROUD_DATA_DIR_VALUE" ]]; then
  printf 'SHROUD_DATA_DIR=%s\n' "$SHROUD_DATA_DIR_VALUE" >>.env
fi
chmod 600 .env 2>/dev/null || true

echo ""
echo "${GREEN}Wrote .env${NC} (mode ${BOLD}${PROXY_MODE}${NC})."
echo "  Web:  ${WEB_PUBLIC_URL}"
echo "  API:  ${API_PUBLIC_URL}/api/v1"
echo "  Data: ${STORAGE_LABEL}"
echo ""
echo "Starting the stack…"
echo ""

shroud_up
shroud_info
