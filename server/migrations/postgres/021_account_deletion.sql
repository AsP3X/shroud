-- Deleted accounts keep a scrubbed users row.
-- Human: conversations, messages, uploads and calls reference users (and devices) with
-- ON DELETE CASCADE, so deleting the row wiped every chat the account was in, the other
-- person's own messages included, whatever their "Let contacts clear chats for me" setting
-- said. DELETE /auth/account now deletes each chat for both (routes/auth.rs) and keeps the row
-- as a placeholder: username, share code and password hash are cleared, so the name and code
-- are free for new signups and nothing can sign in, and its devices are revoked as in 019.
-- Agent: DB forward-only. A live row has username, share_code and password_hash; a deleted
-- row has none of them, so lookups by username or share code never match it.

ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ NULL;

ALTER TABLE users ALTER COLUMN username DROP NOT NULL;
ALTER TABLE users ALTER COLUMN share_code DROP NOT NULL;
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT users_deleted_scrubbed CHECK (
        (
            deleted_at IS NULL
            AND username IS NOT NULL
            AND share_code IS NOT NULL
            AND password_hash IS NOT NULL
        )
        OR (
            deleted_at IS NOT NULL
            AND username IS NULL
            AND share_code IS NULL
            AND password_hash IS NULL
        )
    );
