-- Column grants for the shroud_admin role. Applied by the table owner (the API's role),
-- not by shroud_admin, which cannot grant on tables it does not own. Re-running is safe.
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
