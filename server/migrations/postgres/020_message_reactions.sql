-- Reactions: one sealed record per (message, user). The server sees who reacted to which
-- message and when, never the emoji.
-- Human: A removed reaction keeps its row with ciphertext NULL so a device that was offline
-- learns about the removal from GET /conversations/{peer}/reactions?after_seq=.
-- Agent: DB forward-only; ciphertext only. `seq` is the conversation's change number (see
-- conversation_reaction_seqs); every set, replace and remove takes a new one. Clients keep the
-- highest seq per (message, user) and the highest seq per conversation as their catch-up cursor.

CREATE TABLE message_reactions (
    message_id UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    ciphertext BYTEA,
    seq BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX message_reactions_conversation_seq_idx
    ON message_reactions (conversation_id, seq);

-- Each conversation's reaction change counter.
-- Human: A reaction write bumps it under its row lock, in the same transaction, so within a
-- conversation seq order is commit order: once a reader sees N, every change up to N is visible
-- and every later one gets a higher number. A global sequence cannot promise that — its values
-- commit out of order — and a catch-up cursor taken between two such commits would skip a
-- change for good.
CREATE TABLE conversation_reaction_seqs (
    conversation_id UUID PRIMARY KEY REFERENCES conversations (id) ON DELETE CASCADE,
    seq BIGINT NOT NULL
);

-- How far each participant has seen reactions to their own messages: everything the other
-- participant did after `seen_seq` counts as unseen (the chat list's heart badge).
CREATE TABLE reaction_reads (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    seen_seq BIGINT NOT NULL,
    PRIMARY KEY (user_id, conversation_id)
);
