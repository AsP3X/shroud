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
  if [[ -z "$PROXY_MODE" ]]; then
    PROXY_MODE="$(shroud_env_value PROXY_MODE)"
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
  # A key .env doesn't have is an empty value. Without `|| true`, grep's "no match" fails the
  # pipeline under deploy.sh's pipefail and aborts the deploy.
  { grep -E "^${key}=" "$file" 2>/dev/null || true; } | tail -1 | cut -d= -f2- | tr -d '\r' | tr -d '"' | tr -d "'"
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

shroud_random_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$1"
  else
    dd if=/dev/urandom bs=1 count="$1" 2>/dev/null | od -An -tx1 | tr -d ' \n'
  fi
}

# Replaces KEY's line in .env, or appends one. Written to a temp file and moved into place,
# so an interrupted run never leaves half a .env.
shroud_set_env_value() {
  local key="$1" value="$2" file="${SHROUD_REPO_ROOT}/.env" tmp
  tmp="$(mktemp "${file}.XXXXXX")"
  if grep -qE "^${key}=" "$file"; then
    awk -v k="$key" -v v="$value" 'index($0, k "=") == 1 { print k "=" v; next } { print }' "$file" >"$tmp"
  else
    cat "$file" >"$tmp"
    [[ -z "$(tail -c1 "$file")" ]] || echo >>"$tmp"
    printf '%s=%s\n' "$key" "$value" >>"$tmp"
  fi
  chmod 600 "$tmp"
  mv "$tmp" "$file"
}

# Nebular OS credentials an older .env lacks, generated once and never replaced: Nebular's own
# secret, the API's access key (id + secret), and the /metrics token.
shroud_ensure_nebular_secrets() {
  local file="${SHROUD_REPO_ROOT}/.env" key value added=""
  [[ -f "$file" ]] || return 0
  for key in NOS_JWT_SECRET NEBULAR_ACCESS_KEY_ID NEBULAR_SECRET_ACCESS_KEY NOS_METRICS_TOKEN; do
    value="$(shroud_env_value "$key")"
    [[ -n "$value" && "$value" != "GENERATE_ME" ]] && continue
    if [[ "$key" == NEBULAR_ACCESS_KEY_ID ]]; then
      value="SHRD$(shroud_random_hex 8 | tr '[:lower:]' '[:upper:]')"
    else
      value="$(shroud_random_hex 32)"
    fi
    shroud_set_env_value "$key" "$value"
    added="${added} ${key}"
  done
  if [[ -n "$added" ]]; then
    echo "Added Nebular OS credentials to .env:${added}"
  fi
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

shroud_pg_volume_exists() {
  docker volume ls -q 2>/dev/null | grep -q 'shroud_pg_data$'
}

shroud_diagnose_up() {
  local logs=""
  logs="$(docker logs shroud-api 2>&1 | tail -n 80 || true)"
  if printf '%s' "$logs" | grep -q 'password authentication failed'; then
    printf '\n'
    echo "ERROR: Postgres rejected the API password."
    echo ""
    echo "  The official Postgres image applies POSTGRES_PASSWORD only the first"
    echo "  time the data volume is created. This host already has a volume"
    echo "  (shroud_pg_data), so a new password in .env is ignored by Postgres."
    echo ""
    echo "  Keep existing chat data:"
    echo "    Put the original password in .env as POSTGRES_PASSWORD"
    echo "    (the previous compose default was: shroud)"
    echo "    then:  ./deploy.sh"
    echo ""
    echo "  Wipe the database and use the current .env password:"
    echo "    ./deploy.sh --down --volumes"
    echo "    ./deploy.sh"
    echo ""
  elif printf '%s' "$logs" | grep -q 'database connection failed'; then
    printf '\n'
    echo "ERROR: API could not connect to Postgres."
    echo "  Logs:  ./deploy.sh --logs api"
    echo ""
  fi
}

shroud_up() {
  shroud_ensure_nebular_secrets
  shroud_assert_env
  shroud_ensure_proxy_network
  if ! shroud_compose up -d --build --remove-orphans; then
    shroud_diagnose_up
    return 1
  fi
}

shroud_down() {
  if [[ "${1:-}" == "--volumes" ]]; then
    echo "Removing containers and named volumes (Postgres data will be wiped)…"
    shroud_compose down --volumes --remove-orphans
  else
    shroud_compose down
  fi
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
