-- Whether someone who only knows a user's name can find them.
-- Human: Off, `GET /users/by-username` answers "not found" to everyone but contacts and people
-- with a pending request either way, so the account can only be added with its QR code or share
-- code (docs/privacy-options.md, phase 4). Signing up with a taken name still says it's taken —
-- names stay unique.
-- Agent: DB forward-only. READ by routes::users::get_user_by_username.

ALTER TABLE users
    ADD COLUMN discoverable_by_username BOOLEAN NOT NULL DEFAULT true;
