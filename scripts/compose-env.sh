#!/usr/bin/env bash
# Shared Docker Compose helpers (bash 3.2 compatible — stock macOS).
# shellcheck shell=bash
# Usage: source scripts/compose-env.sh   # from repo root

if [[ -z "${SHROUD_REPO_ROOT:-}" ]]; then
  if [[ -f docker-compose.yml ]]; then
    SHROUD_REPO_ROOT="$(pwd)"
  elif [[ -f "$(dirname "${BASH_SOURCE[0]}")/../docker-compose.yml" ]]; then
    SHROUD_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  else
    echo "ERROR: cannot find docker-compose.yml (run from the repository root)." >&2
    return 1 2>/dev/null || exit 1
  fi
fi

shroud_load_proxy_mode() {
  PROXY_MODE="${PROXY_MODE:-}"
  if [[ -z "$PROXY_MODE" && -f "${SHROUD_REPO_ROOT}/.env" ]]; then
    PROXY_MODE="$(grep -E '^PROXY_MODE=' "${SHROUD_REPO_ROOT}/.env" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r' | tr -d '"' | tr -d "'")"
  fi
  PROXY_MODE="$(printf '%s' "${PROXY_MODE:-local}" | tr '[:upper:]' '[:lower:]')"
  case "$PROXY_MODE" in
    npm|external|proxy) PROXY_MODE="npm" ;;
    *) PROXY_MODE="local" ;;
  esac
  export PROXY_MODE
}

shroud_env_value() {
  local key="$1" file="${SHROUD_REPO_ROOT}/.env"
  [[ -f "$file" ]] || return 0
  grep -E "^${key}=" "$file" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r' | tr -d '"' | tr -d "'"
}

shroud_compose_cli_args() {
  shroud_load_proxy_mode
  local args=(
    -f "${SHROUD_REPO_ROOT}/docker-compose.yml"
  )
  case "$PROXY_MODE" in
    npm) args+=(-f "${SHROUD_REPO_ROOT}/docker-compose.npm.yml") ;;
    *)   args+=(-f "${SHROUD_REPO_ROOT}/docker-compose.local.yml") ;;
  esac
  printf '%s\n' "${args[@]}"
}

shroud_compose() {
  local args=() line
  while IFS= read -r line; do
    [[ -n "$line" ]] && args+=("$line")
  done < <(shroud_compose_cli_args)
  docker compose "${args[@]}" "$@"
}

shroud_ensure_proxy_network() {
  shroud_load_proxy_mode
  [[ "$PROXY_MODE" == "npm" ]] || return 0
  if docker network inspect proxy-network >/dev/null 2>&1; then
    return 0
  fi
  echo "Creating Docker network proxy-network (shared with Nginx Proxy Manager)…"
  docker network create proxy-network >/dev/null
}

shroud_assert_env() {
  local file="${SHROUD_REPO_ROOT}/.env"
  [[ -f "$file" ]] || return 0
  if grep -E '=(GENERATE_ME)[[:space:]]*$' "$file" >/dev/null 2>&1; then
    echo "ERROR: .env still contains GENERATE_ME placeholders." >&2
    echo "  Run ./deploy.sh --init (or copy .env.example and replace every GENERATE_ME)." >&2
    return 1
  fi
}

shroud_up() {
  shroud_assert_env
  shroud_ensure_proxy_network
  shroud_compose up -d --build --remove-orphans
}

shroud_down() {
  shroud_compose down
}

shroud_info() {
  shroud_load_proxy_mode
  local web api
  web="$(shroud_env_value WEB_PUBLIC_URL)"
  api="$(shroud_env_value API_PUBLIC_URL)"
  web="${web:-http://localhost:8081}"
  api="${api:-http://localhost:8080}"

  echo ""
  echo "  Proxy mode:  ${PROXY_MODE}"
  echo "  Web client:  ${web}"
  echo "  API (iOS):   ${api}/api/v1"
  if [[ "$PROXY_MODE" == "npm" ]]; then
    echo ""
    echo "  Nginx Proxy Manager hosts:"
    echo "    web  →  http://shroud-web:80"
    echo "    api  →  http://shroud-api:8080"
  fi
  echo ""
  shroud_compose ps
}
