-- Key bundles: per-device public identity, signed pre-key, one-time pre-keys.
-- Agent: DB forward-only; public key material only — no private keys or plaintext.

CREATE TABLE device_identity_keys (
    device_id UUID PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    registration_id INT NOT NULL,
    public_key BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT device_identity_keys_registration_id_range
        CHECK (registration_id >= 0 AND registration_id <= 16383)
);

CREATE TABLE device_signed_prekeys (
    device_id UUID PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    key_id INT NOT NULL,
    public_key BYTEA NOT NULL,
    signature BYTEA NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT device_signed_prekeys_key_id_range
        CHECK (key_id >= 0 AND key_id <= 16777215)
);

CREATE TABLE device_one_time_prekeys (
    device_id UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    key_id INT NOT NULL,
    public_key BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, key_id),
    CONSTRAINT device_one_time_prekeys_key_id_range
        CHECK (key_id >= 0 AND key_id <= 16777215)
);

CREATE INDEX device_one_time_prekeys_device_id_idx
    ON device_one_time_prekeys (device_id);
