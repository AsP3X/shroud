-- PIN guard: the server half of the web client's PIN unlock (`web/src/crypto/vault.ts`).
-- Human: The browser keeps its vault key wrapped under PIN + pepper. The pepper lives only here
-- and is released only against the PIN-derived auth key, so a copied browser profile cannot be
-- brute-forced offline: every guess is a request, and too many wrong ones delete the pepper.
-- Agent: verifier = SHA-256(auth_key); never store auth_key or the PIN. One guard per device.

CREATE TABLE device_pin_guards (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    verifier BYTEA NOT NULL,
    pepper BYTEA NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT device_pin_guards_one_per_device UNIQUE (device_id)
);
