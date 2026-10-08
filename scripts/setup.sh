#!/usr/bin/env bash
# First-time setup wizard — writes .env; deploy.sh then starts the stack. Bash 3.2 compatible
# (stock macOS). Exits 3 when the user keeps an existing .env.
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

# A re-run keeps what .env already has: every prompt defaults to the current value, every
# secret is reused, and keys the wizard never asks about (APNs, Web Push, client versions,
# TURN extras) are carried over unchanged. Only a first run generates or asks from scratch.
current() {
  shroud_env_value "$1"
}

reuse_or_generate() {
  local existing="$1"
  if [[ -n "$existing" && "$existing" != "GENERATE_ME" ]]; then
    printf '%s' "$existing"
  else
    generate_secret
  fi
}

# The keys this wizard writes. Everything else in the old .env is appended again below.
WIZARD_KEYS=" PROXY_MODE WEB_PUBLIC_URL API_PUBLIC_URL WEB_PORT API_PORT CORS_ALLOWED_ORIGINS POSTGRES_USER POSTGRES_PASSWORD POSTGRES_DB NOS_JWT_SECRET NEBULAR_ACCESS_KEY_ID NEBULAR_SECRET_ACCESS_KEY NOS_METRICS_TOKEN REDIS_PASSWORD COMPOSE_PROFILES TURN_URLS TURN_SECRET ADMIN_PORT ADMIN_PUBLIC_URL ADMIN_DB_PASSWORD ADMIN_DATABASE_URL ADMIN_SECRET_KEY OPERATOR_PORT OPERATOR_TOKEN RUST_LOG RUST_LOG_FORMAT SHROUD_DATA_DIR "

OLD_ENV=""
if [[ -f .env ]]; then
  OLD_ENV="$(cat .env)"
fi

if [[ -f .env && "${SHROUD_SETUP_ASSUME_YES:-}" != "1" ]]; then
  echo ""
  echo "${YELLOW}Existing .env detected.${NC}"
  echo "  Re-running keeps your current values as the defaults (press Enter to keep each one),"
  echo "  reuses every secret, and carries over settings the wizard doesn't ask about."
  echo "  Found: mode ${BOLD}$(current PROXY_MODE)${NC}, web ${BOLD}$(current WEB_PUBLIC_URL)${NC}, API ${BOLD}$(current API_PUBLIC_URL)${NC}"
  printf '  Run setup again? %s[y/N]%s: ' "$DIM" "$NC"
  read -r overwrite || true
  case "$(printf '%s' "$overwrite" | tr '[:upper:]' '[:lower:]')" in
    y|yes) ;;
    *) echo "Cancelled."; exit 3 ;;
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
mode_default="1"
[[ "$(current PROXY_MODE)" == "npm" ]] && mode_default="2"
printf '  %s[%s]%s: ' "$DIM" "$mode_default" "$NC"
if [[ "${SHROUD_SETUP_ASSUME_YES:-}" == "1" ]]; then
  mode_choice="$mode_default"
  echo "$mode_default"
else
  read -r mode_choice || true
  mode_choice="${mode_choice:-$mode_default}"
fi

# Whatever .env holds is the default, exactly as written. Only an empty value falls back to
# the example (npm) or to localhost on the chosen port (local).
url_or() {
  local value="$1" fallback="$2"
  if [[ -n "$value" ]]; then
    printf '%s' "$value"
  else
    printf '%s' "$fallback"
  fi
}
# The API uses these values as browser origins, and an origin needs its scheme. A bare host
# (chat.example.org) gets https:// in proxy mode and http:// on local ports; a trailing slash
# is dropped. The wizard says so when it changed what was typed or stored.
with_scheme() {
  local value="$1" scheme="$2" fixed
  fixed="${value%/}"
  case "$fixed" in
    http://*|https://*) ;;
    "") ;;
    *) fixed="${scheme}://${fixed}" ;;
  esac
  if [[ "$fixed" != "$value" ]]; then
    echo "  Using ${BOLD}${fixed}${NC} (an origin needs its scheme, and no trailing slash)." >&2
  fi
  printf '%s' "$fixed"
}
if [[ "$mode_choice" == "2" ]]; then
  PROXY_MODE="npm"
  WEB_PUBLIC_URL="$(with_scheme "$(prompt "Public web URL" "$(url_or "$(current WEB_PUBLIC_URL)" "https://web.example.com")")" https)"
  API_PUBLIC_URL="$(with_scheme "$(prompt "Public API URL (iOS)" "$(url_or "$(current API_PUBLIC_URL)" "https://api.example.com")")" https)"
  WEB_PORT="8081"
  API_PORT="8080"
else
  PROXY_MODE="local"
  WEB_PORT="$(prompt "Web host port" "$(current WEB_PORT)")"
  API_PORT="$(prompt "API host port" "$(current API_PORT)")"
  WEB_PORT="${WEB_PORT:-8081}"
  API_PORT="${API_PORT:-8080}"
  # A reverse proxy outside this compose file, or a LAN address, may front the local ports;
  # the URL the apps use is asked for, with localhost on the port as the first-run default.
  echo "  The URLs browsers and the apps use. Keep localhost unless something fronts these ports."
  WEB_PUBLIC_URL="$(with_scheme "$(prompt "Public web URL" "$(url_or "$(current WEB_PUBLIC_URL)" "http://localhost:${WEB_PORT}")")" http)"
  API_PUBLIC_URL="$(with_scheme "$(prompt "Public API URL (iOS)" "$(url_or "$(current API_PUBLIC_URL)" "http://localhost:${API_PORT}")")" http)"
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
# The other secrets are reused for the same reason: Nebular remembers the access key it was
# started with, and a new one would lock the API out of the media it already stored.
NOS_JWT_SECRET="$(reuse_or_generate "$(current NOS_JWT_SECRET)")"
NEBULAR_ACCESS_KEY_ID="$(current NEBULAR_ACCESS_KEY_ID)"
if [[ -z "$NEBULAR_ACCESS_KEY_ID" || "$NEBULAR_ACCESS_KEY_ID" == "GENERATE_ME" ]]; then
  NEBULAR_ACCESS_KEY_ID="SHRD$(generate_hex 8 | tr '[:lower:]' '[:upper:]')"
  echo "  Nebular OS secret and the API's access key: ${GREEN}generated${NC}"
else
  echo "  Nebular OS secret and the API's access key: ${GREEN}reused from .env${NC}"
fi
NEBULAR_SECRET_ACCESS_KEY="$(reuse_or_generate "$(current NEBULAR_SECRET_ACCESS_KEY)")"
NOS_METRICS_TOKEN="$(reuse_or_generate "$(current NOS_METRICS_TOKEN)")"
REDIS_PASSWORD="$(reuse_or_generate "$(current REDIS_PASSWORD)")"
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
# The current relay host wins as the default; a first npm setup suggests the API's host.
EXISTING_TURN_URLS="$(current TURN_URLS)"
if [[ -n "$EXISTING_TURN_URLS" ]]; then
  TURN_HOST_DEFAULT="$(printf '%s' "$EXISTING_TURN_URLS" | cut -d, -f1 | sed -E 's#^turns?:##; s#[:?/].*$##')"
elif [[ "$PROXY_MODE" == "npm" && -z "$OLD_ENV" ]]; then
  TURN_HOST_DEFAULT="$(printf '%s' "$API_PUBLIC_URL" | sed -E 's#^[A-Za-z]+://##; s#[:/].*$##')"
else
  TURN_HOST_DEFAULT=""
fi
if [[ -n "$TURN_HOST_DEFAULT" ]]; then
  echo "  Enter keeps ${BOLD}${TURN_HOST_DEFAULT}${NC}; type ${BOLD}-${NC} to turn the relay off."
fi
TURN_HOST="$(prompt "Relay hostname or IP" "$TURN_HOST_DEFAULT")"
[[ "$TURN_HOST" == "-" ]] && TURN_HOST=""
if [[ -n "$TURN_HOST" ]]; then
  TURN_URLS="turn:${TURN_HOST}:3478?transport=udp,turn:${TURN_HOST}:3478?transport=tcp"
  COMPOSE_PROFILES="calls"
  echo "  TURN relay: ${GREEN}on${NC} at ${TURN_HOST}"
else
  TURN_URLS=""
  COMPOSE_PROFILES=""
  echo "  TURN relay: ${DIM}off${NC}"
fi

echo ""
echo "${BOLD}── Admin console ──${NC}"
echo "  The operator console is its own site, not a page of the web client. Local mode binds it"
echo "  to 127.0.0.1. Nginx Proxy Manager mode puts it on proxy-network as shroud-admin:8082."
case ",$(current COMPOSE_PROFILES)," in
  *,admin,*) admin_default="y"; admin_hint="[Y/n]" ;;
  *) admin_default="n"; admin_hint="[y/N]" ;;
esac
printf '  Enable the admin console? %s%s%s: ' "$DIM" "$admin_hint" "$NC"
admin_choice=""
if [[ "${SHROUD_SETUP_ASSUME_YES:-}" == "1" ]]; then
  echo "$admin_default"
  admin_choice="$admin_default"
else
  read -r admin_choice || true
fi
# Off keeps the console's settings and secrets in .env, so turning it back on later finds the
# same key: the authenticator secrets in the database are sealed with ADMIN_SECRET_KEY, and a
# new key would make every operator re-enrol. Only the profile decides whether it runs.
ADMIN_PORT="$(current ADMIN_PORT)"
ADMIN_PORT="${ADMIN_PORT:-8082}"
ADMIN_PUBLIC_URL="$(current ADMIN_PUBLIC_URL)"
ADMIN_DB_PASSWORD="$(current ADMIN_DB_PASSWORD)"
ADMIN_DATABASE_URL="$(current ADMIN_DATABASE_URL)"
ADMIN_SECRET_KEY="$(current ADMIN_SECRET_KEY)"
OPERATOR_PORT="$(current OPERATOR_PORT)"
OPERATOR_PORT="${OPERATOR_PORT:-8090}"
OPERATOR_TOKEN="$(current OPERATOR_TOKEN)"
case "$(printf '%s' "${admin_choice:-$admin_default}" | tr '[:upper:]' '[:lower:]')" in
  y|yes)
    if [[ -n "$COMPOSE_PROFILES" ]]; then
      COMPOSE_PROFILES="${COMPOSE_PROFILES},admin"
    else
      COMPOSE_PROFILES="admin"
    fi
    admin_port_default="$(current ADMIN_PORT)"
    ADMIN_PORT="$(prompt "Admin host port" "${admin_port_default:-8082}")"
    if [[ "$PROXY_MODE" == "npm" ]]; then
      admin_url_default="$(current ADMIN_PUBLIC_URL)"
      case "$admin_url_default" in https://*) ;; *) admin_url_default="https://admin.example.com" ;; esac
      ADMIN_PUBLIC_URL="$(prompt "Public admin URL" "$admin_url_default")"
    else
      ADMIN_PUBLIC_URL="http://127.0.0.1:${ADMIN_PORT}"
    fi
    ADMIN_DB_PASSWORD="$(reuse_or_generate "$(current ADMIN_DB_PASSWORD)")"
    ADMIN_DATABASE_URL="postgres://shroud_admin:${ADMIN_DB_PASSWORD}@postgres:5432/shroud"
    ADMIN_SECRET_KEY="$(reuse_or_generate "$(current ADMIN_SECRET_KEY)")"
    OPERATOR_PORT="$(current OPERATOR_PORT)"
    OPERATOR_PORT="${OPERATOR_PORT:-8090}"
    OPERATOR_TOKEN="$(reuse_or_generate "$(current OPERATOR_TOKEN)")"
    echo "  Admin console: ${GREEN}on${NC} at ${ADMIN_PUBLIC_URL}"
    ;;
  *)
    if [[ -n "$ADMIN_SECRET_KEY" ]]; then
      echo "  Admin console: ${DIM}off${NC} (its settings and secrets stay in .env for when you turn it back on)"
    else
      echo "  Admin console: ${DIM}off${NC}"
    fi
    ;;
esac

# Extra origins added by hand stay as long as the web URL is still among them.
CORS_ALLOWED_ORIGINS="$(current CORS_ALLOWED_ORIGINS)"
case ",${CORS_ALLOWED_ORIGINS}," in
  *",${WEB_PUBLIC_URL},"*) ;;
  *) CORS_ALLOWED_ORIGINS="$WEB_PUBLIC_URL" ;;
esac
RUST_LOG="$(current RUST_LOG)"
RUST_LOG="${RUST_LOG:-info}"
RUST_LOG_FORMAT="$(current RUST_LOG_FORMAT)"
RUST_LOG_FORMAT="${RUST_LOG_FORMAT:-text}"

# Lines of the old .env the wizard does not manage: APNs, Web Push, UnifiedPush, client
# versions, TURN extras, and anything added by hand. Comments and blank lines are kept too,
# so the file reads as it did.
CARRIED=""
carried_count=0
if [[ -n "$OLD_ENV" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    key="${line%%=*}"
    case "$line" in
      [A-Z_]*=*)
        case "$WIZARD_KEYS" in
          *" ${key} "*) continue ;;
        esac
        carried_count=$((carried_count + 1))
        ;;
    esac
    CARRIED="${CARRIED}${line}"$'\n'
  done <<< "$OLD_ENV"
fi

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
ADMIN_PORT=${ADMIN_PORT}
ADMIN_PUBLIC_URL=${ADMIN_PUBLIC_URL}
ADMIN_DB_PASSWORD=${ADMIN_DB_PASSWORD}
ADMIN_DATABASE_URL=${ADMIN_DATABASE_URL}
ADMIN_SECRET_KEY=${ADMIN_SECRET_KEY}
OPERATOR_PORT=${OPERATOR_PORT}
OPERATOR_TOKEN=${OPERATOR_TOKEN}
RUST_LOG=${RUST_LOG}
RUST_LOG_FORMAT=${RUST_LOG_FORMAT}
EOF
if [[ -n "$SHROUD_DATA_DIR_VALUE" ]]; then
  printf 'SHROUD_DATA_DIR=%s\n' "$SHROUD_DATA_DIR_VALUE" >>.env
fi
if [[ "$carried_count" -gt 0 ]]; then
  {
    echo ""
    echo "# Kept from the previous .env (settings the setup wizard does not ask about)."
    printf '%s' "$CARRIED"
  } >>.env
fi
chmod 600 .env 2>/dev/null || true

echo ""
echo "${GREEN}Wrote .env${NC} (mode ${BOLD}${PROXY_MODE}${NC})."
if [[ "$carried_count" -gt 0 ]]; then
  echo "  Kept ${carried_count} settings the wizard doesn't ask about (push, client versions, extras)."
fi
echo "  Web:  ${WEB_PUBLIC_URL}"
echo "  API:  ${API_PUBLIC_URL}/api/v1"
if [[ -n "$ADMIN_PUBLIC_URL" ]]; then
  echo "  Admin: ${ADMIN_PUBLIC_URL}"
fi
echo "  Data: ${STORAGE_LABEL}"
