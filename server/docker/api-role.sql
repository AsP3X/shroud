-- The API's own Postgres role (security audit H5). Run as the superuser (POSTGRES_USER) with
-- psql variables `pwd` (API_DB_PASSWORD) and `dbname`, before the API starts. Re-running is safe.
--
-- The official image makes POSTGRES_USER a superuser, and a superuser cannot be made an ordinary
-- role again (it is the bootstrap role). So the API connects as shroud_api instead: it owns the
-- database, schema public and every table in it, and may do nothing else. A trigger or function
-- that someone plants for the API to run then runs with an ordinary role's rights. The superuser
-- stays for deploys and maintenance; no service connects with it.

SET client_min_messages = warning;

-- \gset keeps the row (the password) in a psql variable instead of printing it to the log.
SELECT set_config('shroud.api_password', :'pwd', false) AS api_password_set \gset
DO $body$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shroud_api') THEN
    EXECUTE format(
      'CREATE ROLE shroud_api LOGIN PASSWORD %L',
      current_setting('shroud.api_password')
    );
  ELSE
    -- The password in .env wins, so changing it there and deploying again takes effect.
    EXECUTE format(
      'ALTER ROLE shroud_api LOGIN PASSWORD %L',
      current_setting('shroud.api_password')
    );
  END IF;
END
$body$;
ALTER ROLE shroud_api NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE shroud_api SET search_path = public;

-- Migration 001 creates pgcrypto. An extension needs more than an ordinary role has on a database
-- that never had it, so the superuser creates it first and the migration finds it.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

ALTER DATABASE :"dbname" OWNER TO shroud_api;
ALTER SCHEMA public OWNER TO shroud_api;

-- Everything the API made while it ran as the superuser: tables (their sequences and indexes
-- move with them), views and sequences of their own. Extension members stay with the extension.
DO $body$
DECLARE
  rel record;
BEGIN
  FOR rel IN
    SELECT c.oid::regclass AS name, c.relkind
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relkind IN ('r', 'p', 'v', 'm', 'S')
      AND c.relowner <> 'shroud_api'::regrole
      AND NOT EXISTS (
        SELECT 1 FROM pg_depend d
        WHERE d.classid = 'pg_class'::regclass
          AND d.objid = c.oid
          AND d.deptype IN ('e', 'a', 'i')
      )
  LOOP
    EXECUTE format(
      'ALTER %s %s OWNER TO shroud_api',
      CASE rel.relkind
        WHEN 'v' THEN 'VIEW'
        WHEN 'm' THEN 'MATERIALIZED VIEW'
        WHEN 'S' THEN 'SEQUENCE'
        ELSE 'TABLE'
      END,
      rel.name
    );
  END LOOP;
END
$body$;
