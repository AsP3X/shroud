-- Denormalize last message time for cheap conversation list ordering.
-- Agent: DB forward-only; backfill from messages; updated on send.

ALTER TABLE conversations
    ADD COLUMN last_message_at TIMESTAMPTZ NULL;

UPDATE conversations c
SET last_message_at = (
    SELECT MAX(m.created_at)
    FROM messages m
    WHERE m.conversation_id = c.id
);

CREATE INDEX conversations_last_message_at_idx
    ON conversations (last_message_at DESC NULLS LAST);
