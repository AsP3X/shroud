#!/bin/sh
# Ensure media blob dir is writable by the runtime user (volume mounts are often root-owned).
set -e

MEDIA_DIR="${MEDIA_DATA_DIR:-/data/shroud-media}"
mkdir -p "$MEDIA_DIR"

# Human: The server binds OPERATOR_HOST only as an IP address. Compose names an alias the API has
# on shroud-internal alone, so the operator listener binds that one address and a container on
# another network the API joins (proxy-network with the npm overlay) cannot reach it (security
# audit A7). A name that resolves to nothing stops the start: falling back to every address would
# reopen the port. An address another container answers for fails the bind the same way.
case "${OPERATOR_HOST:-}" in
  "" | *:*) ;;
  *[!0-9.]*)
    operator_addr="$(getent ahostsv4 "$OPERATOR_HOST" | awk 'NR == 1 { print $1 }')"
    if [ -z "$operator_addr" ]; then
      echo "entrypoint: OPERATOR_HOST '$OPERATOR_HOST' does not resolve to an IPv4 address" >&2
      exit 1
    fi
    OPERATOR_HOST="$operator_addr"
    export OPERATOR_HOST
    ;;
esac

if [ "$(id -u)" = "0" ]; then
  chown -R nobody:nogroup "$MEDIA_DIR" 2>/dev/null || true
  chmod 755 "$MEDIA_DIR" 2>/dev/null || true
  exec gosu nobody:nogroup /usr/local/bin/shroud-server "$@"
fi

exec /usr/local/bin/shroud-server "$@"
