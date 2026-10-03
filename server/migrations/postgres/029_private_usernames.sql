-- Usernames leave the database. Login uses SHA-256 of the case-folded name, which does not
-- reveal the name. A person learns someone else's name only from a seal that account made
-- for them after they added each other (`contact_sealed_names`). The server cannot read it.
-- Agent: DB forward-only. pgcrypto `digest` is the same SHA-256 the clients send.

ALTER TABLE users ADD COLUMN username_hash BYTEA;

UPDATE users
SET username_hash = digest(convert_to(username, 'UTF8'), 'sha256')
WHERE username IS NOT NULL;

ALTER TABLE users DROP CONSTRAINT users_deleted_scrubbed;
ALTER TABLE users DROP CONSTRAINT users_username_key;
DROP INDEX IF EXISTS users_username_idx;

ALTER TABLE users DROP COLUMN username;

ALTER TABLE users
    ADD CONSTRAINT users_deleted_scrubbed CHECK (
        (
            deleted_at IS NULL
            AND username_hash IS NOT NULL
            AND share_code IS NOT NULL
            AND password_hash IS NOT NULL
        )
        OR (
            deleted_at IS NOT NULL
            AND username_hash IS NULL
            AND share_code IS NULL
            AND password_hash IS NULL
        )
    );

CREATE UNIQUE INDEX users_username_hash_uidx ON users (username_hash) WHERE username_hash IS NOT NULL;

-- `sealed` is a sealed box (`ek`, `ct`, `t`) of the owner's username, made for `peer_id`.
CREATE TABLE contact_sealed_names (
    owner_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    peer_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sealed TEXT NOT NULL,
    PRIMARY KEY (owner_id, peer_id)
);
