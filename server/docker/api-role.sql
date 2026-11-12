-- The API's own Postgres role (security audit H5). Run as the superuser (POSTGRES_USER) inside the
-- postgres container, with psql variable `dbname`, before the API starts. Re-running is safe. The
-- password comes from the container's API_DB_PASSWORD, never from `psql -v`: process arguments
-- show on the host to anyone with ps (security audit A6).
--
-- The official image makes POSTGRES_USER a superuser, and a superuser cannot be made an ordinary
-- role again (it is the bootstrap role). So the API connects as shroud_api instead: it owns the
-- database, schema public and every table in it, and may do nothing else. A trigger or function
-- that someone plants for the API to run then runs with an ordinary role's rights. The superuser
-- stays for deploys and maintenance; no service connects with it.

SET client_min_messages = warning;
-- Only pg_catalog, never public: shroud_api owns public, and an operator or function it creates
-- there that matches the argument types exactly would win over pg_catalog's and run as the
-- superuser (security audit A1, CVE-2018-1058). Everything below names public itself.
SET search_path = pg_catalog, pg_temp;

\getenv pwd API_DB_PASSWORD
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
-- The same for every later superuser session in this database (the deploy scripts' other SQL,
-- grants.sql, a maintainer's psql), whatever its role-wide setting. A per-database setting on this
-- role outranks the database's own default, which shroud_api as the owner can change.
ALTER ROLE CURRENT_USER IN DATABASE :"dbname" SET search_path = pg_catalog, pg_temp;

-- Migration 001 creates pgcrypto. An extension needs more than an ordinary role has on a database
-- that never had it, so the superuser creates it first and the migration finds it.
CREATE EXTENSION IF NOT EXISTS "pgcrypto" SCHEMA public;

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
