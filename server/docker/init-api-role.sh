#!/bin/sh
# Creates the API's Postgres role on a fresh data directory (api-role.sql). An existing volume
# skips every script in docker-entrypoint-initdb.d; deploy then applies api-role.sql itself,
# before it starts the API.
set -eu

if [ -z "${API_DB_PASSWORD:-}" ]; then
  echo "shroud_api: API_DB_PASSWORD is empty, role not created"
  exit 0
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v pwd="$API_DB_PASSWORD" -v dbname="$POSTGRES_DB" -f /shroud/api-role.sql
