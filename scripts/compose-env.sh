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
  # pipeline under deploy.sh's pipefail and aborts the deploy. A hand-edited file may indent
  # the key, prefix it with `export`, or put spaces around `=`; all of those still read.
  { grep -E "^[[:space:]]*(export[[:space:]]+)?${key}[[:space:]]*=" "$file" 2>/dev/null || true; } \
    | tail -1 \
    | sed -E "s/^[[:space:]]*(export[[:space:]]+)?${key}[[:space:]]*=[[:space:]]*//; s/[[:space:]]+\$//" \
    | tr -d '\r' | tr -d '"' | tr -d "'"
}

# A SHROUD_DATA_DIR value as an absolute path: relative to the repository root (not the caller's
# directory), `~` for $HOME, no trailing slash. Empty stays empty (named volumes).
shroud_resolve_data_dir() {
  local dir="$1"
  [[ -n "$dir" ]] || return 0
  case "$dir" in
    "~") dir="$HOME" ;;
    "~/"*) dir="${HOME}/${dir:2}" ;;
    /*) ;;
    *) dir="${SHROUD_REPO_ROOT}/${dir#./}" ;;
  esac
  while [[ "$dir" == */ && "$dir" != / ]]; do dir="${dir%/}"; done
  printf '%s\n' "$dir"
}

# Where the stack keeps its data, from .env only: a one-off environment variable would switch
# storage for one command and leave the next on the other side. Sets SHROUD_DATA_DIR to the
# absolute folder, exported for the compose overlay, or unsets it for named volumes.
shroud_load_data_dir() {
  SHROUD_DATA_DIR="$(shroud_resolve_data_dir "$(shroud_env_value SHROUD_DATA_DIR)")"
  if [[ -n "$SHROUD_DATA_DIR" ]]; then
    export SHROUD_DATA_DIR
  else
    unset SHROUD_DATA_DIR
  fi
}

shroud_compose_cli_args() {
  shroud_load_proxy_mode
  shroud_load_data_dir
  local args=(
    -f "${SHROUD_REPO_ROOT}/docker-compose.yml"
  )
  case "$PROXY_MODE" in
    npm) args+=(-f "${SHROUD_REPO_ROOT}/docker-compose.npm.yml") ;;
    *)   args+=(-f "${SHROUD_REPO_ROOT}/docker-compose.local.yml") ;;
  esac
  if [[ -n "${SHROUD_DATA_DIR:-}" ]]; then
    args+=(-f "${SHROUD_REPO_ROOT}/docker-compose.data-dir.yml")
  fi
  printf '%s\n' "${args[@]}"
}

shroud_compose() {
  local args=() line
  # Here too, not only in the subshell below: the overlay reads the exported path.
  shroud_load_data_dir
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

# Credentials an older .env lacks, generated once and never replaced: Nebular's own secret, the
# API's access key (id + secret), the /metrics token, and the Redis password.
shroud_ensure_nebular_secrets() {
  local file="${SHROUD_REPO_ROOT}/.env" key value added=""
  [[ -f "$file" ]] || return 0
  for key in NOS_JWT_SECRET NEBULAR_ACCESS_KEY_ID NEBULAR_SECRET_ACCESS_KEY NOS_METRICS_TOKEN REDIS_PASSWORD; do
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
    echo "Added generated credentials to .env:${added}"
  fi
}

# The secret coturn and the API share (docs/calls.md), for an .env from before calls had one.
# Generated once and kept: a new one only ends the TURN logins handed out so far.
shroud_ensure_turn_secret() {
  local file="${SHROUD_REPO_ROOT}/.env" value
  [[ -f "$file" ]] || return 0
  value="$(shroud_env_value TURN_SECRET)"
  [[ -n "$value" && "$value" != "GENERATE_ME" ]] && return 0
  shroud_set_env_value TURN_SECRET "$(shroud_random_hex 32)"
  echo "Added the TURN relay secret to .env: TURN_SECRET"
}

# True when COMPOSE_PROFILES in .env lists this compose profile.
shroud_profile_enabled() {
  [[ ",$(shroud_env_value COMPOSE_PROFILES)," == *",$1,"* ]]
}

# Secrets the console needs once its profile is on. Generated once, like TURN_SECRET, and
# left empty while the profile is off so a stock deploy does not invent a console.
shroud_ensure_admin_secrets() {
  local file="${SHROUD_REPO_ROOT}/.env" key value port
  [[ -f "$file" ]] || return 0
  shroud_profile_enabled admin || return 0
  for key in ADMIN_DB_PASSWORD ADMIN_SECRET_KEY OPERATOR_TOKEN; do
    value="$(shroud_env_value "$key")"
    [[ -n "$value" && "$value" != "GENERATE_ME" ]] && continue
    shroud_set_env_value "$key" "$(shroud_random_hex 32)"
    echo "Added the admin console secret to .env: ${key}"
  done
  value="$(shroud_env_value ADMIN_DATABASE_URL)"
  if [[ -z "$value" || "$value" == "GENERATE_ME" ]]; then
    shroud_set_env_value ADMIN_DATABASE_URL \
      "postgres://shroud_admin:$(shroud_env_value ADMIN_DB_PASSWORD)@postgres:5432/shroud"
  fi
  port="$(shroud_env_value ADMIN_PORT)"
  [[ -n "$port" ]] || shroud_set_env_value ADMIN_PORT 8082
  value="$(shroud_env_value OPERATOR_PORT)"
  [[ -n "$value" ]] || shroud_set_env_value OPERATOR_PORT 8090
  value="$(shroud_env_value ADMIN_PUBLIC_URL)"
  if [[ -z "$value" ]]; then
    shroud_load_proxy_mode
    port="$(shroud_env_value ADMIN_PORT)"
    if [[ "$PROXY_MODE" == "npm" ]]; then
      shroud_set_env_value ADMIN_PUBLIC_URL "https://admin.example.com"
    else
      shroud_set_env_value ADMIN_PUBLIC_URL "http://127.0.0.1:${port:-8082}"
    fi
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

# Removes KEY's lines from .env, the same way.
shroud_unset_env_value() {
  local key="$1" file="${SHROUD_REPO_ROOT}/.env" tmp
  [[ -f "$file" ]] && grep -qE "^${key}=" "$file" || return 0
  tmp="$(mktemp "${file}.XXXXXX")"
  awk -v k="$key" 'index($0, k "=") != 1' "$file" >"$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$file"
}

# Storage is either named volumes, written "" below, or a folder: SHROUD_DATA_DIR resolved by
# shroud_resolve_data_dir. Each holds three kinds of data, as <volume>:<subfolder>.
SHROUD_DATA_KINDS="shroud_pg_data:database shroud_nebular_data:nebular shroud_media_data:media"
# The image that copies and wipes data owned by the containers' users; the stack already pulls it.
SHROUD_DATA_TOOL_IMAGE="postgres:16-alpine"

# The Compose project name, which prefixes the named volumes (<project>_shroud_pg_data).
shroud_project_name() {
  local name
  name="$(cd "$SHROUD_REPO_ROOT" && docker compose -f docker-compose.yml config --no-interpolate 2>/dev/null |
    sed -n 's/^name: //p' | head -1 || true)"
  if [[ -z "$name" ]]; then
    name="$(basename "$SHROUD_REPO_ROOT" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')"
  fi
  printf '%s\n' "$name"
}

# Makes the three data folders in $1 at the usual 755, whatever the caller's umask: under the
# wizard's 077 they would be 700 and owned by the host user.
shroud_make_data_dirs() {
  (umask 022 && mkdir -p "${1}/database" "${1}/nebular" "${1}/media")
}

# Nebular's entrypoint hands only /data/blobs and /data/meta to its user (uid 10001), not /data
# itself. A new named volume takes /data's owner from the image; a folder keeps the host user's,
# and Nebular then fails with "Permission denied". The host user can't chown to 10001, so a
# one-off Nebular container does it as root (the service keeps CAP_CHOWN for its entrypoint).
# Postgres and the API chown their own mount points.
shroud_own_nebular_dir() {
  local out
  if ! out="$(shroud_compose run --rm --no-deps -T --user 0 --entrypoint chown nebular 10001:10001 /data 2>&1)"; then
    printf '%s\n' "$out" >&2
    echo "ERROR: cannot give ${SHROUD_DATA_DIR}/nebular to Nebular's user (uid 10001)." >&2
    return 1
  fi
}

shroud_storage_label() {
  if [[ -n "$1" ]]; then
    printf 'the folder %s\n' "$1"
  else
    printf 'named Docker volumes\n'
  fi
}

# What `docker run -v` mounts for one kind of data: a volume name or a folder.
shroud_storage_source() {
  local storage="$1" project="$2" kind="$3"
  if [[ -n "$storage" ]]; then
    printf '%s/%s\n' "$storage" "${kind#*:}"
  else
    printf '%s_%s\n' "$project" "${kind%%:*}"
  fi
}

# Whether a data folder holds anything. A folder we can't look into counts as full: Postgres
# keeps its own at mode 700 for uid 70.
shroud_dir_has_data() {
  [[ -d "$1" ]] || return 1
  [[ -r "$1" && -x "$1" ]] || return 0
  [[ -n "$(ls -A "$1" 2>/dev/null)" ]]
}

# Whether storage $1 has kind $3's data; $2 is the project name. A volume counts once it exists.
shroud_storage_has() {
  local source
  source="$(shroud_storage_source "$1" "$2" "$3")"
  if [[ -n "$1" ]]; then
    shroud_dir_has_data "$source"
  else
    docker volume inspect "$source" >/dev/null 2>&1
  fi
}

# Whether Postgres has already initialized its data in storage $1 (default: the current one), so
# POSTGRES_PASSWORD no longer applies.
shroud_pg_data_exists() {
  local storage
  if (( $# )); then
    storage="$1"
  else
    shroud_load_data_dir
    storage="${SHROUD_DATA_DIR:-}"
  fi
  shroud_storage_has "$storage" "$(shroud_project_name)" "shroud_pg_data:database"
}

# Asks a yes/no question, default no. --yes answers yes; without a terminal the answer is no.
shroud_confirm() {
  local answer=""
  if [[ "${SHROUD_SETUP_ASSUME_YES:-}" == "1" ]]; then
    printf '%s [y/N]: y (--yes)\n' "$1"
    return 0
  fi
  if [[ ! -t 0 ]]; then
    printf '%s [y/N]: no terminal to answer; run it from one, or add --yes\n' "$1" >&2
    return 1
  fi
  printf '%s [y/N]: ' "$1"
  read -r answer || true
  case "$(printf '%s' "$answer" | tr '[:upper:]' '[:lower:]')" in
    y|yes) return 0 ;;
    *) return 1 ;;
  esac
}

# Prints the commands that copy every kind of data from storage $1 to storage $2 and switch with
# $3, for a stack that is down. Nothing is deleted.
shroud_print_copy_commands() {
  local from="$1" to="$2" switch_cmd="$3" project kind src dst
  project="$(shroud_project_name)"
  echo "  ./deploy.sh --down"
  if [[ -n "$to" ]]; then
    echo "  mkdir -p \"${to}/database\" \"${to}/nebular\" \"${to}/media\""
  fi
  for kind in $SHROUD_DATA_KINDS; do
    shroud_storage_has "$from" "$project" "$kind" || continue
    src="$(shroud_storage_source "$from" "$project" "$kind")"
    dst="$(shroud_storage_source "$to" "$project" "$kind")"
    if [[ -z "$to" ]]; then
      echo "  docker volume create --label com.docker.compose.project=${project} --label com.docker.compose.volume=${kind%%:*} ${dst}"
    fi
    echo "  docker run --rm -v \"${src}:/from:ro\" -v \"${dst}:/to\" ${SHROUD_DATA_TOOL_IMAGE} cp -a /from/. /to/"
  done
  echo "  ${switch_cmd}"
}

# Prints how to remove storage $1 once the data has moved on; nothing here removes it.
shroud_print_cleanup_commands() {
  local storage="$1" project kind volumes=""
  project="$(shroud_project_name)"
  if [[ -n "$storage" ]]; then
    echo "  docker run --rm -v \"${storage}:/data\" ${SHROUD_DATA_TOOL_IMAGE} rm -rf /data/database /data/nebular /data/media"
  else
    for kind in $SHROUD_DATA_KINDS; do
      shroud_storage_has "" "$project" "$kind" && volumes="${volumes} $(shroud_storage_source "" "$project" "$kind")"
    done
    [[ -z "$volumes" ]] || echo "  docker volume rm${volumes}"
  fi
}

# Copies every kind of data storage $1 has into storage $2, through a container that keeps each
# file's owner. The stack must be down; storage $1 is left as it is. Returns 1 at the first
# failure (callers run it where set -e is off).
shroud_copy_storage() {
  local from="$1" to="$2" project kind src dst
  project="$(shroud_project_name)"
  if [[ -n "$to" ]]; then
    shroud_make_data_dirs "$to" || return 1
  fi
  for kind in $SHROUD_DATA_KINDS; do
    shroud_storage_has "$from" "$project" "$kind" || continue
    src="$(shroud_storage_source "$from" "$project" "$kind")"
    dst="$(shroud_storage_source "$to" "$project" "$kind")"
    if [[ -z "$to" ]]; then
      # Labelled as Compose labels its own, or every `up` warns that it didn't create it.
      docker volume create --label "com.docker.compose.project=${project}" \
        --label "com.docker.compose.volume=${kind%%:*}" "$dst" >/dev/null || return 1
    fi
    echo "Copying ${src} → ${dst}…"
    docker run --rm -v "${src}:/from:ro" -v "${dst}:/to" "$SHROUD_DATA_TOOL_IMAGE" cp -a /from/. /to/ || return 1
  done
}

# Gets a switch of storage ready before .env changes: $1 is the new SHROUD_DATA_DIR value (empty
# for named volumes), $2 the command that makes the switch, for the instructions. When the data
# would stay behind on the old side, returns 1 having changed nothing, unless SHROUD_MIGRATE=1
# and the user confirms the copy. Stops a running stack. Never deletes or overwrites data.
# Callers run it as `… || exit 1`, which turns set -e off in here: every step checks itself.
shroud_prepare_storage_switch() {
  local new_value="$1" switch_cmd="$2" from to project kind from_label to_label
  shroud_load_data_dir
  from="${SHROUD_DATA_DIR:-}"
  to="$(shroud_resolve_data_dir "$new_value")"
  [[ "$from" != "$to" ]] || return 0
  if [[ "$to" == "/" ]]; then
    echo "ERROR: the data folder can't be / itself." >&2
    return 1
  fi
  project="$(shroud_project_name)"
  from_label="$(shroud_storage_label "$from")"
  to_label="$(shroud_storage_label "$to")"
  local copy=0
  if shroud_pg_data_exists "$from"; then
    if shroud_pg_data_exists "$to"; then
      if [[ "${SHROUD_MIGRATE:-}" == "1" ]]; then
        echo "ERROR: there is already a database in ${to_label}; --migrate never overwrites data." >&2
        echo "  Switch without --migrate to use the data there, or empty it first." >&2
        return 1
      fi
      echo "Note: ${from_label} and ${to_label} both hold a database."
      echo "  Shroud switches to the one in ${to_label}; the data in ${from_label} is left as it is."
    elif [[ "${SHROUD_MIGRATE:-}" == "1" ]]; then
      for kind in $SHROUD_DATA_KINDS; do
        if shroud_storage_has "$to" "$project" "$kind"; then
          echo "ERROR: $(shroud_storage_source "$to" "$project" "$kind") already holds data; --migrate never overwrites data." >&2
          return 1
        fi
      done
      echo "This copies the data in ${from_label} to ${to_label}."
      echo "  The stack is stopped for the copy; the data in ${from_label} is left as it is."
      if ! shroud_confirm "  Copy the data now?"; then
        echo "Nothing was changed."
        return 1
      fi
      copy=1
    else
      echo "ERROR: this server's data is in ${from_label}, and there is none in ${to_label}." >&2
      echo "  Switching now would start Shroud on an empty database. Nothing was changed." >&2
      echo "" >&2
      echo "  Copy the data first, with the stack down:" >&2
      shroud_print_copy_commands "$from" "$to" "$switch_cmd" >&2
      echo "" >&2
      echo "  Or let deploy.sh copy it, after asking: ${switch_cmd} --migrate" >&2
      echo "  Either way the data in ${from_label} stays; remove it yourself once the switch works." >&2
      return 1
    fi
  elif [[ "${SHROUD_MIGRATE:-}" == "1" ]]; then
    echo "Nothing to copy: there is no database in ${from_label}."
  fi
  if [[ -n "$(shroud_compose ps -q 2>/dev/null)" ]]; then
    echo "Stopping the stack to switch storage…"
    if ! shroud_compose down; then
      echo "ERROR: could not stop the stack; storage was not switched." >&2
      return 1
    fi
  fi
  if (( copy )); then
    if ! shroud_copy_storage "$from" "$to"; then
      echo "ERROR: copying failed; storage was not switched, and the data in ${from_label} is unchanged." >&2
      echo "  Remove the partial copy in ${to_label} before trying again:" >&2
      shroud_print_cleanup_commands "$to" >&2
      return 1
    fi
    echo "Copied. The data in ${from_label} is still there; once Shroud works from ${to_label}, remove it with:"
    shroud_print_cleanup_commands "$from"
  fi
}

# Where Postgres keeps its data, for messages.
shroud_pg_data_location() {
  shroud_load_data_dir
  if [[ -n "${SHROUD_DATA_DIR:-}" ]]; then
    printf '%s/database\n' "$SHROUD_DATA_DIR"
  else
    printf 'the volume %s_shroud_pg_data\n' "$(shroud_project_name)"
  fi
}

shroud_diagnose_up() {
  local logs=""
  logs="$(docker logs shroud-api 2>&1 | tail -n 80 || true)"
  if printf '%s' "$logs" | grep -q 'password authentication failed'; then
    printf '\n'
    echo "ERROR: Postgres rejected the API password."
    echo ""
    echo "  The official Postgres image applies POSTGRES_PASSWORD only the first"
    echo "  time it sets up its data. This host already has that data"
    echo "  ($(shroud_pg_data_location)), so a new password in .env is ignored by Postgres."
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

# The web bundle's build id: a hash of what goes into the web image, so a redeploy that
# changes the web client offers open tabs a reload and one that doesn't stays quiet. Compose
# bakes it into the bundle (VITE_WEB_BUILD); once the stack is up, shroud_up writes it to
# .shroud-run/web-build, which the API reads on each question (GET /client-version) without
# being restarted. Outside a git checkout, or when a file can't be read, every deploy gets a
# new one.
shroud_web_build_id() {
  local files="" id=""
  if command -v git >/dev/null 2>&1 && git -C "$SHROUD_REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
    # The image's files: web/.dockerignore leaves out the top-level Markdown and .env files;
    # .gitignore the rest. quotePath=false keeps non-ASCII names as they are on disk.
    files="$(cd "$SHROUD_REPO_ROOT" && git -c core.quotePath=false ls-files -co --exclude-standard -- web |
      while IFS= read -r f; do
        [[ "$f" == web/*.md && "$f" != web/*/* ]] && continue
        [[ -f "$f" ]] && printf '%s\n' "$f"
      done || true)"
  fi
  if [[ -n "$files" ]]; then
    id="$(cd "$SHROUD_REPO_ROOT" && { printf '%s\n' "$files"; printf '%s\n' "$files" | git hash-object --stdin-paths; } |
      git hash-object --stdin 2>/dev/null | cut -c1-12)" || id=""
  fi
  if [[ "${#id}" -eq 12 ]]; then
    printf '%s\n' "$id"
  else
    shroud_random_hex 6
  fi
}

# Sets SHROUD_WEB_BUILD once per run, before anything builds the web image.
shroud_export_web_build() {
  if [[ -z "${SHROUD_WEB_BUILD:-}" ]]; then
    SHROUD_WEB_BUILD="$(shroud_web_build_id)"
  fi
  export SHROUD_WEB_BUILD
}

# Tells the running API which bundle the web container now serves. Written only after `up`
# succeeded, so tabs aren't offered a reload into a container that isn't there yet; the
# directory is bind-mounted read-only into the API (docker-compose.yml).
shroud_publish_web_build() {
  local dir="${SHROUD_REPO_ROOT}/.shroud-run" tmp
  if ! tmp="$(mktemp "${dir}/web-build.XXXXXX" 2>/dev/null)"; then
    warn_web_build "cannot write ${dir} — open tabs won't be offered a reload"
    return 0
  fi
  printf '%s\n' "$SHROUD_WEB_BUILD" >"$tmp"
  chmod 644 "$tmp"
  mv "$tmp" "${dir}/web-build" || warn_web_build "cannot replace ${dir}/web-build"
}

warn_web_build() {
  printf 'WARNING: %s\n' "$*" >&2
}

shroud_up() {
  shroud_ensure_nebular_secrets
  shroud_ensure_turn_secret
  shroud_ensure_admin_secrets
  shroud_assert_env
  shroud_ensure_proxy_network
  shroud_export_web_build
  # Created here, not by Docker: a missing bind-mount source would be made root-owned.
  mkdir -p "${SHROUD_REPO_ROOT}/.shroud-run"
  shroud_load_data_dir
  if [[ -n "${SHROUD_DATA_DIR:-}" ]]; then
    if ! shroud_make_data_dirs "$SHROUD_DATA_DIR"; then
      echo "ERROR: cannot create the data folders in ${SHROUD_DATA_DIR} (SHROUD_DATA_DIR in .env)." >&2
      return 1
    fi
    shroud_own_nebular_dir || return 1
  fi
  if ! shroud_compose up -d --build --remove-orphans; then
    shroud_diagnose_up
    return 1
  fi
  shroud_apply_admin_db
  shroud_publish_web_build
}

# The init script only runs on an empty data directory. This creates shroud_admin on a volume
# that already existed, then re-applies the column grants (the API role owns those tables).
shroud_apply_admin_db() {
  local user db pass
  shroud_profile_enabled admin || return 0
  user="$(shroud_env_value POSTGRES_USER)"
  db="$(shroud_env_value POSTGRES_DB)"
  pass="$(shroud_env_value ADMIN_DB_PASSWORD)"
  user="${user:-shroud}"
  db="${db:-shroud}"
  [[ -n "$pass" ]] || {
    echo "ERROR: the admin profile is on and ADMIN_DB_PASSWORD is empty." >&2
    return 1
  }
  shroud_compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$user" -d "$db" \
    -v pwd="$pass" -v dbname="$db" <<'SQL'
SELECT set_config('shroud.admin_password', :'pwd', false);
DO $body$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shroud_admin') THEN
    EXECUTE format(
      'CREATE ROLE shroud_admin LOGIN PASSWORD %L',
      current_setting('shroud.admin_password')
    );
  END IF;
END
$body$;
GRANT CONNECT, CREATE ON DATABASE :"dbname" TO shroud_admin;
GRANT USAGE ON SCHEMA public TO shroud_admin;
SQL
  shroud_compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$user" -d "$db" \
    -f - < "${SHROUD_REPO_ROOT}/admin/api/grants.sql"
}

# Empties the three data folders in $1 and makes them again. Their contents belong to uid 70,
# 10001 and nobody, so a container removes them. Nothing else in $1 is touched.
shroud_wipe_data_dir() {
  local dir="$1"
  [[ -d "$dir" ]] || return 0
  docker run --rm -v "${dir}:/data" "$SHROUD_DATA_TOOL_IMAGE" rm -rf /data/database /data/nebular /data/media
  shroud_make_data_dirs "$dir"
}

shroud_down() {
  if [[ "${1:-}" == "--volumes" ]]; then
    shroud_load_data_dir
    if [[ -n "${SHROUD_DATA_DIR:-}" ]]; then
      echo "Removing containers and wiping the Postgres, Nebular and media data in ${SHROUD_DATA_DIR}…"
      shroud_compose down --volumes --remove-orphans
      shroud_wipe_data_dir "$SHROUD_DATA_DIR"
    else
      echo "Removing containers and named volumes (Postgres, Nebular and media data will be wiped)…"
      shroud_compose down --volumes --remove-orphans
    fi
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

  shroud_load_data_dir
  echo ""
  echo "  Proxy mode:  ${PROXY_MODE}"
  if [[ -n "${SHROUD_DATA_DIR:-}" ]]; then
    echo "  Storage:     ${SHROUD_DATA_DIR} (SHROUD_DATA_DIR)"
  else
    echo "  Storage:     named Docker volumes"
  fi
  echo "  Web client:  ${web}"
  echo "  API (iOS):   ${api}/api/v1"
  if [[ ",$(shroud_env_value COMPOSE_PROFILES)," == *",calls,"* ]]; then
    local min max
    min="$(shroud_env_value TURN_MIN_PORT)"
    max="$(shroud_env_value TURN_MAX_PORT)"
    echo "  TURN relay:  $(shroud_env_value TURN_URLS)"
    echo "               open UDP/TCP 3478 and UDP ${min:-49160}-${max:-49259}"
  else
    echo "  TURN relay:  off (calls use STUN only; ./deploy.sh --init to turn it on)"
  fi
  if shroud_profile_enabled admin; then
    echo "  Admin console: $(shroud_env_value ADMIN_PUBLIC_URL)"
  else
    echo "  Admin console: off (./deploy.sh --init to turn it on)"
  fi
  if [[ "$PROXY_MODE" == "npm" ]]; then
    echo ""
    echo "  Nginx Proxy Manager hosts:"
    echo "    web  →  http://shroud-web:80"
    echo "    api  →  http://shroud-api:8080"
    if shroud_profile_enabled admin; then
      echo "    admin →  http://shroud-admin:8082"
    fi
  fi
  echo ""
  shroud_compose ps
}
