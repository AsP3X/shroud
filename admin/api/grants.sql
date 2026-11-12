-- Column grants for the shroud_admin role, and one view. Applied by the superuser or the table
-- owner, never by shroud_admin, which cannot grant on tables it does not own. Re-running is safe.
-- Where the API's tables belong to a role of its own (shroud_api, server/docker/api-role.sql), the
-- grants and the view are made as that role, so the view belongs to the API like its tables do and
-- a migration of its own can drop it.
--
-- Deliberately absent: users.username_hash, users.password_hash, devices.sealed_name,
-- messages.body, media_objects.bucket, media_objects.object_key, push_tokens.apns_token,
-- web_push_subscriptions.endpoint, and every key, verifier and pepper column.

-- Only pg_catalog, never public: shroud_api owns public, and an operator or function it creates
-- there that matches the argument types exactly would win over pg_catalog's and run as whoever
-- applies this file (security audit A1, CVE-2018-1058). Every table below is named with its schema.
SET search_path = pg_catalog, pg_temp;

DO $body$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
    WHERE c.oid = to_regclass('public.users') AND r.rolname = 'shroud_api'
  ) THEN
    SET ROLE shroud_api;
  END IF;
END
$body$;

GRANT SELECT (id, created_at, deleted_at) ON public.users TO shroud_admin;
GRANT SELECT (id, user_id, created_at, last_seen_at, revoked_at) ON public.devices TO shroud_admin;
GRANT SELECT (device_id, revoked_at, last_used_at) ON public.sessions TO shroud_admin;
GRANT SELECT (device_id, kind) ON public.push_tokens TO shroud_admin;
GRANT SELECT (device_id, client) ON public.web_push_subscriptions TO shroud_admin;
GRANT SELECT (device_id, enabled) ON public.device_notification_settings TO shroud_admin;
GRANT SELECT (id, uploader_user_id, size_bytes, message_id) ON public.media_objects TO shroud_admin;
GRANT SELECT (device_id) ON public.device_pin_guards TO shroud_admin;

-- iPhones whose pushes still carry readable ids: an APNs token but no payload key to seal them
-- with (older builds). A view, so the privacy checks count them without reading payload_key.
CREATE OR REPLACE VIEW public.admin_unsealed_push_devices AS
    SELECT DISTINCT pt.device_id
    FROM public.push_tokens pt
    JOIN public.devices d ON d.id = pt.device_id AND d.revoked_at IS NULL
    WHERE NOT EXISTS (
        SELECT 1
        FROM public.push_tokens alert
        WHERE alert.device_id = pt.device_id
          AND alert.kind = 'alert'
          AND alert.payload_key IS NOT NULL
    );
GRANT SELECT ON public.admin_unsealed_push_devices TO shroud_admin;

RESET ROLE;

-- The audit log, and schema admin around it, belong to the role applying this file (the superuser
-- in a Compose deploy), neither to the console's role nor to the API's: the console adds and reads
-- entries, and even with its own login stolen it cannot change, remove or drop one (a schema's
-- owner may drop anything in it, so the schema moves too; the console keeps USAGE and CREATE for
-- its own tables and migrations). Skipped until the console has created its schema (deploy waits
-- for it first). A later console migration that alters the audit log has to be applied by this
-- role.
DO $body$
BEGIN
  IF to_regclass('admin.audit_log') IS NOT NULL THEN
    EXECUTE 'ALTER SCHEMA admin OWNER TO ' || quote_ident(current_user);
    GRANT USAGE, CREATE ON SCHEMA admin TO shroud_admin;
    EXECUTE 'ALTER TABLE admin.audit_log OWNER TO ' || quote_ident(current_user);
    REVOKE ALL ON admin.audit_log FROM shroud_admin;
    GRANT SELECT, INSERT ON admin.audit_log TO shroud_admin;
  END IF;
END
$body$;

-- Console migration 004 (security audit A5): each entry keeps the operator's name as it was when
-- the entry was written. Once the log belongs to this role, only this role can add that column,
-- so the console's start waits for it here. The names come from admin.operators, which the
-- console's role owns, so they are read as that role: this role only changes its own table. The
-- update right that needs is granted and revoked inside this one statement, so no other session
-- ever sees it.
DO $body$
DECLARE
  audit_log oid := to_regclass('admin.audit_log');
BEGIN
  IF audit_log IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM pg_attribute
    WHERE attrelid = audit_log AND attname = 'operator_name' AND NOT attisdropped
  ) THEN
    ALTER TABLE admin.audit_log ADD COLUMN operator_name text NULL;
    GRANT UPDATE (operator_name) ON admin.audit_log TO shroud_admin;
    SET LOCAL ROLE shroud_admin;
    UPDATE admin.audit_log a SET operator_name = o.name
      FROM admin.operators o WHERE o.id = a.operator_id;
    RESET ROLE;
    REVOKE UPDATE (operator_name) ON admin.audit_log FROM shroud_admin;
    ALTER TABLE admin.audit_log ALTER COLUMN operator_name SET NOT NULL;
    CREATE INDEX audit_log_operator_idx ON admin.audit_log (operator_id, at DESC, id DESC);
  END IF;
END
$body$;

-- Hand the session back as it came, like RESET ROLE above: a caller that runs this file on a
-- pooled connection (the console's tests) goes on to name the API's tables without a schema.
RESET search_path;
