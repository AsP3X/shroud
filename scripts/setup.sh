#!/usr/bin/env bash
# First-time setup wizard — writes .env. Bash 3.2 compatible (stock macOS).
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

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

generate_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    dd if=/dev/urandom bs=1 count=32 2>/dev/null | od -An -tx1 | tr -d ' \n'
  fi
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
echo "${BOLD}── Secrets ──${NC}"
POSTGRES_PASSWORD="$(generate_secret)"
NOS_JWT_SECRET="$(generate_secret)"
NOS_SIGNING_SECRET="$(generate_secret)"
echo "  Postgres password, Nebular JWT, and signing secret: ${GREEN}generated${NC}"

CORS_ALLOWED_ORIGINS="$WEB_PUBLIC_URL"

NEBULAR_CONTEXT="${NEBULAR_CONTEXT:-../ownly/nebular-os}"
if [[ ! -d "$NEBULAR_CONTEXT" ]]; then
  echo "${YELLOW}  Note: ${NEBULAR_CONTEXT} is missing — set NEBULAR_CONTEXT in .env if nebular-os lives elsewhere.${NC}"
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
NOS_SIGNING_SECRET=${NOS_SIGNING_SECRET}
RUST_LOG=info
RUST_LOG_FORMAT=text
NEBULAR_CONTEXT=${NEBULAR_CONTEXT}
EOF
chmod 600 .env 2>/dev/null || true

echo ""
echo "${GREEN}Wrote .env${NC} (mode ${BOLD}${PROXY_MODE}${NC})."
echo "  Web:  ${WEB_PUBLIC_URL}"
echo "  API:  ${API_PUBLIC_URL}/api/v1"
echo ""
echo "Starting the stack…"
echo ""

# shellcheck disable=SC1091
source scripts/compose-env.sh
shroud_up
shroud_info
