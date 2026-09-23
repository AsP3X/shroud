-- Soft-removed devices: Settings → Devices marks the row instead of deleting it.
-- Human: messages, uploads and calls reference the sending device with ON DELETE CASCADE, so a
-- hard delete wiped everything that device ever sent from both participants' history. A revoked
-- device keeps its row (and so its history) but loses its sessions, keys, push token and PIN
-- guard, drops out of GET /devices, delivery fan-out and key bundles, and frees a cap slot.
-- Agent: DB forward-only; revoked rows are never un-revoked or reclaimed by login.

ALTER TABLE devices ADD COLUMN revoked_at TIMESTAMPTZ NULL;

CREATE INDEX devices_user_active_idx ON devices (user_id) WHERE revoked_at IS NULL;
