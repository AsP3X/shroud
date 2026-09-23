-- Reactions: one sealed record per (message, user). The server sees who reacted to which
-- message and when, never the emoji.
-- Human: A removed reaction keeps its row with ciphertext NULL so a device that was offline
-- learns about the removal from GET /conversations/{peer}/reactions?after_seq=.
-- Agent: DB forward-only; ciphertext only. seq is a global, strictly increasing change cursor
-- (every set, replace and remove takes a new value); clients keep the highest seq per
-- (message, user) and the highest seq per conversation as their catch-up cursor.

CREATE SEQUENCE message_reaction_seq;

CREATE TABLE message_reactions (
    message_id UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    ciphertext BYTEA,
    seq BIGINT NOT NULL DEFAULT nextval('message_reaction_seq'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX message_reactions_conversation_seq_idx
    ON message_reactions (conversation_id, seq);
