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
--
-- The console's role owns the log until this block first runs, and an owner can attach things to
-- its table that keep working after the table changes hands, then with this role's rights (security
-- audit A2): a rule's actions are checked against the table owner, an index expression or extended
-- statistic runs as the owner whenever ANALYZE, VACUUM or REINDEX does (autovacuum included), a
-- grant to PUBLIC survives the owner change, and a trigger, policy, inheritance or a view in the
-- table's place can rewrite, hide or forge entries. Refuse to take the log, or to keep it on a
-- later deploy, while any of that is there. Migration 001 makes none of it: the foreign key's
-- triggers are internal, and its defaults, constraints and indexes use pg_catalog only. This needs
-- the search_path above, so the catalog names and operators below are pg_catalog's.
--
-- The check runs after the owner change, in the same block, so that nothing slips in between: the
-- lock first stops rule, trigger, policy and index changes, the owner change then takes away the
-- console's right to grant or attach anything, and a refusal rolls both back with the rest of the
-- block. A check before the change would pass while something was being attached behind it.
DO $body$
DECLARE
  audit_log oid := to_regclass('admin.audit_log');
  attached text;
BEGIN
  IF audit_log IS NOT NULL THEN
    LOCK TABLE admin.audit_log IN ACCESS EXCLUSIVE MODE;
    EXECUTE 'ALTER SCHEMA admin OWNER TO ' || quote_ident(current_user);
    EXECUTE 'ALTER TABLE admin.audit_log OWNER TO ' || quote_ident(current_user);

    SELECT string_agg(what, E'\n' ORDER BY what) INTO attached FROM (
      SELECT 'admin.audit_log is not a permanent plain table' AS what
        FROM pg_class WHERE oid = audit_log AND (relkind <> 'r' OR relpersistence <> 'p')
      UNION ALL
      SELECT pg_describe_object('pg_rewrite'::regclass, oid, 0)
        FROM pg_rewrite WHERE ev_class = audit_log AND rulename <> '_RETURN'
      UNION ALL
      SELECT pg_describe_object('pg_trigger'::regclass, oid, 0)
        FROM pg_trigger WHERE tgrelid = audit_log AND NOT tgisinternal
      UNION ALL
      SELECT pg_describe_object('pg_policy'::regclass, oid, 0)
        FROM pg_policy WHERE polrelid = audit_log
      UNION ALL
      SELECT 'row-level security on admin.audit_log'
        FROM pg_class WHERE oid = audit_log AND (relrowsecurity OR relforcerowsecurity)
      UNION ALL
      SELECT 'inheritance between ' || inhrelid::regclass::text || ' and ' || inhparent::regclass::text
        FROM pg_inherits WHERE inhrelid = audit_log OR inhparent = audit_log
      UNION ALL
      -- Functions, operators and types from outside pg_catalog in the table's columns, indexes,
      -- defaults, constraints or statistics: code the console's role wrote.
      SELECT pg_describe_object(d.classid, d.objid, d.objsubid) || ' uses '
             || pg_describe_object(d.refclassid, d.refobjid, 0)
        FROM pg_depend d
        JOIN (
          SELECT 'pg_class'::regclass AS classid, audit_log AS objid
          UNION ALL SELECT 'pg_class'::regclass, indexrelid FROM pg_index WHERE indrelid = audit_log
          UNION ALL SELECT 'pg_attrdef'::regclass, oid FROM pg_attrdef WHERE adrelid = audit_log
          UNION ALL SELECT 'pg_constraint'::regclass, oid FROM pg_constraint WHERE conrelid = audit_log
          UNION ALL SELECT 'pg_statistic_ext'::regclass, oid FROM pg_statistic_ext WHERE stxrelid = audit_log
        ) part ON part.classid = d.classid AND part.objid = d.objid
        WHERE CASE d.refclassid
          WHEN 'pg_proc'::regclass THEN (SELECT pronamespace FROM pg_proc WHERE oid = d.refobjid)
          WHEN 'pg_operator'::regclass THEN (SELECT oprnamespace FROM pg_operator WHERE oid = d.refobjid)
          WHEN 'pg_type'::regclass THEN (SELECT typnamespace FROM pg_type WHERE oid = d.refobjid)
        END <> 'pg_catalog'::regnamespace
      UNION ALL
      -- Anyone but this role and the console holding a privilege, on the table or a column. The
      -- console's own are replaced below.
      SELECT 'a privilege on admin.audit_log for '
             || CASE acl.grantee WHEN 0 THEN 'PUBLIC' ELSE acl.grantee::regrole::text END
        FROM pg_class c, aclexplode(c.relacl) acl
        WHERE c.oid = audit_log
          AND acl.grantee <> c.relowner
          AND acl.grantee NOT IN (SELECT oid FROM pg_roles WHERE rolname IN ('shroud_admin', current_user))
      UNION ALL
      SELECT 'a privilege on column admin.audit_log.' || a.attname::text || ' for '
             || CASE acl.grantee WHEN 0 THEN 'PUBLIC' ELSE acl.grantee::regrole::text END
        FROM pg_attribute a, pg_class c, aclexplode(a.attacl) acl
        WHERE a.attrelid = audit_log AND c.oid = audit_log
          AND acl.grantee <> c.relowner
          AND acl.grantee NOT IN (SELECT oid FROM pg_roles WHERE rolname IN ('shroud_admin', current_user))
    ) found;
    IF attached IS NOT NULL THEN
      RAISE EXCEPTION 'admin.audit_log carries objects that would run or act with the rights of %, so it was not taken over', current_user
        USING DETAIL = attached,
              HINT = 'See "If grants.sql refuses the audit log" in docs/admin.md. The audit log and schema admin were not changed.';
    END IF;

    GRANT USAGE, CREATE ON SCHEMA admin TO shroud_admin;
    REVOKE ALL ON admin.audit_log FROM shroud_admin;
    GRANT SELECT, INSERT ON admin.audit_log TO shroud_admin;
  END IF;
END
$body$;

-- Console migration 004 (security audit A5): each entry keeps the operator's name as it was when
-- the entry was written. Once the log belongs to this role, only this role can add that column,
-- so the console's start waits for it here.
--
-- The names come from admin.operators, which the console's role owns, so it can attach code there
-- that a read by this role would run with this role's rights: a view or rule in the table's place,
-- a row-level security policy (this role bypasses them, a non-superuser owner of the API tables may
-- not), or a function in an index expression or predicate, a statistic, a default, a constraint
-- or a column type (the planner evaluates the constant ones while it plans the read). SET ROLE is
-- no protection: code run under it can RESET ROLE. So the table is locked, checked for all of
-- that, and read only when nothing is there. Anything found stops the deploy with nothing
-- changed, as for the audit log above.
DO $body$
DECLARE
  audit_log oid := to_regclass('admin.audit_log');
  operators oid;
  attached text;
BEGIN
  IF audit_log IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM pg_attribute
    WHERE attrelid = audit_log AND attname = 'operator_name' AND NOT attisdropped
  ) THEN
    LOCK TABLE admin.operators IN ACCESS EXCLUSIVE MODE;
    operators := to_regclass('admin.operators');
    SELECT string_agg(what, E'\n' ORDER BY what) INTO attached FROM (
      SELECT 'admin.operators is not a permanent plain table' AS what
        FROM pg_class WHERE oid = operators AND (relkind <> 'r' OR relpersistence <> 'p')
      UNION ALL
      SELECT pg_describe_object('pg_rewrite'::regclass, oid, 0)
        FROM pg_rewrite WHERE ev_class = operators
      UNION ALL
      SELECT pg_describe_object('pg_policy'::regclass, oid, 0)
        FROM pg_policy WHERE polrelid = operators
      UNION ALL
      SELECT 'row-level security on admin.operators'
        FROM pg_class WHERE oid = operators AND (relrowsecurity OR relforcerowsecurity)
      UNION ALL
      SELECT 'inheritance between ' || inhrelid::regclass::text || ' and ' || inhparent::regclass::text
        FROM pg_inherits WHERE inhrelid = operators OR inhparent = operators
      UNION ALL
      SELECT pg_describe_object(d.classid, d.objid, d.objsubid) || ' uses '
             || pg_describe_object(d.refclassid, d.refobjid, 0)
        FROM pg_depend d
        JOIN (
          SELECT 'pg_class'::regclass AS classid, operators AS objid
          UNION ALL SELECT 'pg_class'::regclass, indexrelid FROM pg_index WHERE indrelid = operators
          UNION ALL SELECT 'pg_attrdef'::regclass, oid FROM pg_attrdef WHERE adrelid = operators
          UNION ALL SELECT 'pg_constraint'::regclass, oid FROM pg_constraint WHERE conrelid = operators
          UNION ALL SELECT 'pg_statistic_ext'::regclass, oid FROM pg_statistic_ext WHERE stxrelid = operators
        ) part ON part.classid = d.classid AND part.objid = d.objid
        WHERE CASE d.refclassid
          WHEN 'pg_proc'::regclass THEN (SELECT pronamespace FROM pg_proc WHERE oid = d.refobjid)
          WHEN 'pg_operator'::regclass THEN (SELECT oprnamespace FROM pg_operator WHERE oid = d.refobjid)
          WHEN 'pg_type'::regclass THEN (SELECT typnamespace FROM pg_type WHERE oid = d.refobjid)
        END <> 'pg_catalog'::regnamespace
    ) found;
    IF attached IS NOT NULL THEN
      RAISE EXCEPTION 'admin.operators carries objects that would run with the rights of %, so the audit log''s operator names were not filled in', current_user
        USING DETAIL = attached,
              HINT = 'See "If grants.sql refuses the audit log" in docs/admin.md. Nothing was changed.';
    END IF;

    ALTER TABLE admin.audit_log ADD COLUMN operator_name text NULL;
    UPDATE admin.audit_log a SET operator_name = o.name
      FROM ONLY admin.operators o WHERE o.id = a.operator_id;
    ALTER TABLE admin.audit_log ALTER COLUMN operator_name SET NOT NULL;
    CREATE INDEX audit_log_operator_idx ON admin.audit_log (operator_id, at DESC, id DESC);
  END IF;
END
$body$;

-- Hand the session back as it came, like RESET ROLE above: a caller that runs this file on a
-- pooled connection (the console's tests) goes on to name the API's tables without a schema.
RESET search_path;
