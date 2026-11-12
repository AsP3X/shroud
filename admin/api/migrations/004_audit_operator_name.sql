-- The operator's name as it was when each audit entry was written. The audit page used to join
-- the live name from admin.operators, which the console's role can update, so a stolen console
-- login could rename an operator and so re-attribute every entry they had made (security audit
-- A5). Entries written before this file keep the name the operator has when it runs.
--
-- Only the log's owner can add the column. Until grants.sql has first run that is the console's
-- role, which adds it here. After that the log belongs to the superuser, and grants.sql adds the
-- column the same way on its next run (deploy runs it after every start). Until then this file
-- stops the console's start, so the console never writes entries without a name. The column is
-- plain text with a pg_catalog index, so it attaches nothing that grants.sql refuses to adopt.

DO $body$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_attribute
    WHERE attrelid = 'admin.audit_log'::pg_catalog.regclass
      AND attname = 'operator_name'
      AND NOT attisdropped
  ) THEN
    IF NOT pg_catalog.pg_has_role(
      (SELECT relowner FROM pg_catalog.pg_class WHERE oid = 'admin.audit_log'::pg_catalog.regclass),
      'USAGE'
    ) THEN
      RAISE EXCEPTION 'admin.audit_log needs column operator_name, and only its owner can add it'
        USING HINT = 'Run ./deploy.sh --admin, or apply admin/api/grants.sql as the superuser.';
    END IF;
    ALTER TABLE admin.audit_log ADD COLUMN operator_name TEXT NULL;
    UPDATE admin.audit_log a SET operator_name = o.name
      FROM admin.operators o WHERE o.id = a.operator_id;
    ALTER TABLE admin.audit_log ALTER COLUMN operator_name SET NOT NULL;
    CREATE INDEX audit_log_operator_idx ON admin.audit_log (operator_id, at DESC, id DESC);
  END IF;
END
$body$;
