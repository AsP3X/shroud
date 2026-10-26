-- Column grants for the shroud_admin role, and one view. Applied by the table owner (the API's
-- role), not by shroud_admin, which cannot grant on tables it does not own. Re-running is safe.
--
-- Deliberately absent: users.username_hash, users.password_hash, devices.sealed_name,
-- messages.body, media_objects.bucket, media_objects.object_key, push_tokens.apns_token,
-- web_push_subscriptions.endpoint, and every key, verifier and pepper column.

GRANT SELECT (id, created_at, deleted_at) ON users TO shroud_admin;
GRANT SELECT (id, user_id, created_at, last_seen_at, revoked_at) ON devices TO shroud_admin;
GRANT SELECT (device_id, revoked_at, last_used_at) ON sessions TO shroud_admin;
GRANT SELECT (device_id, kind) ON push_tokens TO shroud_admin;
GRANT SELECT (device_id, client) ON web_push_subscriptions TO shroud_admin;
GRANT SELECT (device_id, enabled) ON device_notification_settings TO shroud_admin;
GRANT SELECT (id, uploader_user_id, size_bytes, message_id) ON media_objects TO shroud_admin;
GRANT SELECT (device_id) ON device_pin_guards TO shroud_admin;

-- iPhones whose pushes still carry readable ids: an APNs token but no payload key to seal them
-- with (older builds). A view, so the privacy checks count them without reading payload_key.
CREATE OR REPLACE VIEW admin_unsealed_push_devices AS
    SELECT DISTINCT pt.device_id
    FROM push_tokens pt
    JOIN devices d ON d.id = pt.device_id AND d.revoked_at IS NULL
    WHERE NOT EXISTS (
        SELECT 1
        FROM push_tokens alert
        WHERE alert.device_id = pt.device_id
          AND alert.kind = 'alert'
          AND alert.payload_key IS NOT NULL
    );
GRANT SELECT ON admin_unsealed_push_devices TO shroud_admin;

-- The audit log, and schema admin around it, belong to this role, not the console's: the console
-- adds and reads entries, and even with its own login stolen it cannot change, remove or drop one
-- (a schema's owner may drop anything in it, so the schema moves too; the console keeps USAGE and
-- CREATE for its own tables and migrations). Skipped until the console has created its schema
-- (deploy waits for it first). A later console migration that alters the audit log has to be
-- applied by this role.
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
