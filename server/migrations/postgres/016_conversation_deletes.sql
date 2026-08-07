-- Whole-chat delete: per-user consent flag + per-user clear watermark.
-- Agent: DB forward-only; no plaintext. Clearing hides history for one participant;
-- ciphertext is only DELETEd once both participants have cleared past it.

-- Opt-in: lets a contact's "delete for both" also clear this user's copy of the chat.
-- Default false — nobody can wipe your history unless you allowed it.
ALTER TABLE users
    ADD COLUMN allow_peer_chat_delete BOOLEAN NOT NULL DEFAULT false;

-- One row per (participant, conversation): messages at or before `cleared_at` are
-- invisible to that participant. Cheaper than a message_hides row per message.
CREATE TABLE conversation_clears (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    cleared_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, conversation_id)
);

CREATE INDEX conversation_clears_conversation_idx
    ON conversation_clears (conversation_id);
