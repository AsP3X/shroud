-- Device names are sealed by the account's own devices; the server keeps only ciphertext.
-- Human: `name` held labels like "Niklas's iPhone" or "Chrome on Mac" in the clear. Clients now
-- seal the name under a key from the encryption phrase (docs/architecture.md, sealed device
-- names), so only the account's devices can read it. The old plaintext is dropped, not kept
-- beside the new column: each client seals its name again after its next unlock.
-- Agent: DB forward-only. WRITTEN by routes::devices::put_device_name (reset on device reclaim);
-- READ by GET /devices, /auth/me, register and login.

ALTER TABLE devices
    ADD COLUMN sealed_name BYTEA NULL
        CONSTRAINT devices_sealed_name_size CHECK (octet_length(sealed_name) BETWEEN 28 AND 512);

ALTER TABLE devices DROP COLUMN name;
