-- Auth milestone: devices and sessions for multi-device opaque tokens.
-- Agent: DB forward-only; token_hash only (never raw tokens); no message plaintext.

CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NULL
);

CREATE INDEX devices_user_id_idx ON devices (user_id);

CREATE TABLE sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ NULL,
    last_used_at TIMESTAMPTZ NULL,
    CONSTRAINT sessions_token_hash_unique UNIQUE (token_hash)
);

CREATE INDEX sessions_device_id_idx ON sessions (device_id);

-- Human: One live session per device so login supersedes the previous token on that device.
CREATE UNIQUE INDEX sessions_one_active_per_device
    ON sessions (device_id)
    WHERE revoked_at IS NULL;
