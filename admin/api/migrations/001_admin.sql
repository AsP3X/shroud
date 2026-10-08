-- The console's own tables, in schema admin, owned by the shroud_admin role that applies this
-- file. Passwords are argon2id, TOTP secrets are ciphertext, session ids and recovery codes
-- and setup tokens are hashes. Nothing else about an operator is stored.

CREATE SCHEMA IF NOT EXISTS admin;

CREATE TABLE admin.operators (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    role TEXT NOT NULL CHECK (role IN ('read', 'write')),
    -- Null until the one-time setup link is used.
    password_hash TEXT NULL,
    totp_secret_enc BYTEA NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_sign_in_at TIMESTAMPTZ NULL
);

CREATE TABLE admin.operator_sessions (
    id_hash BYTEA PRIMARY KEY,
    operator_id UUID NOT NULL REFERENCES admin.operators (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reauth_until TIMESTAMPTZ NULL,
    csrf TEXT NOT NULL
);

CREATE TABLE admin.recovery_codes (
    operator_id UUID NOT NULL REFERENCES admin.operators (id) ON DELETE CASCADE,
    code_hash BYTEA NOT NULL,
    used_at TIMESTAMPTZ NULL,
    PRIMARY KEY (operator_id, code_hash)
);

CREATE TABLE admin.audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    at TIMESTAMPTZ NOT NULL DEFAULT now(),
    operator_id UUID NOT NULL REFERENCES admin.operators (id),
    action TEXT NOT NULL,
    target_kind TEXT NULL,
    target_id UUID NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('ok', 'refused', 'failed')),
    detail TEXT NULL
);

CREATE INDEX audit_log_at_idx ON admin.audit_log (at DESC, id DESC);
CREATE INDEX audit_log_target_idx ON admin.audit_log (target_id);

CREATE TABLE admin.setup_links (
    token_hash BYTEA PRIMARY KEY,
    operator_id UUID NOT NULL REFERENCES admin.operators (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ NULL
);
