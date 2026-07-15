-- Message hide (for me) and tombstone (for everyone).
-- Agent: DB forward-only; clearing ciphertext is intentional unsend — no plaintext.

ALTER TABLE messages
    ALTER COLUMN ciphertext DROP NOT NULL;

ALTER TABLE messages
    ADD COLUMN deleted_for_everyone_at TIMESTAMPTZ NULL;

CREATE TABLE message_hides (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, message_id)
);

CREATE INDEX message_hides_message_id_idx ON message_hides (message_id);
