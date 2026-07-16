-- Random non-guessable share codes for QR / deep links (not derived from UUID).
-- Agent: DB forward-only; codes are public identifiers, not secrets.

ALTER TABLE users
    ADD COLUMN share_code TEXT;

-- Backfill existing accounts (hex is fine for uniqueness; new signups use Crockford alphabet).
UPDATE users
SET share_code = upper(substr(md5(id::text || random()::text), 1, 10))
WHERE share_code IS NULL;

ALTER TABLE users
    ALTER COLUMN share_code SET NOT NULL;

CREATE UNIQUE INDEX users_share_code_uidx ON users (share_code);

ALTER TABLE users
    ADD CONSTRAINT users_share_code_format_check
        CHECK (share_code ~ '^[A-Z0-9]{8,16}$');
