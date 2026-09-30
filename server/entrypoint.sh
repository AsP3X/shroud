#!/bin/sh
# Ensure media blob dir is writable by the runtime user (volume mounts are often root-owned).
set -e

MEDIA_DIR="${MEDIA_DATA_DIR:-/data/shroud-media}"
mkdir -p "$MEDIA_DIR"

if [ "$(id -u)" = "0" ]; then
  chown -R nobody:nogroup "$MEDIA_DIR" 2>/dev/null || true
  chmod 755 "$MEDIA_DIR" 2>/dev/null || true
  exec gosu nobody:nogroup /usr/local/bin/shroud-server "$@"
fi

exec /usr/local/bin/shroud-server "$@"
