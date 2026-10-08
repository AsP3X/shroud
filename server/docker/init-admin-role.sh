#!/bin/sh
# Creates the console's Postgres role on a fresh data directory. An existing volume skips
# every script in docker-entrypoint-initdb.d; deploy then creates the role itself.
set -eu

if [ -z "${ADMIN_DB_PASSWORD:-}" ]; then
  echo "shroud_admin: ADMIN_DB_PASSWORD is empty, role not created"
  exit 0
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v pwd="$ADMIN_DB_PASSWORD" -v dbname="$POSTGRES_DB" <<'SQL'
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
