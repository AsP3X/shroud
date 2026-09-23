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
    -- Copy of messages.sender_user_id, which never changes (the message FK removes the row):
    -- lets the unseen count reach reactions to one person's messages without a join.
    message_sender_id UUID NOT NULL,
    ciphertext BYTEA,
    seq BIGINT NOT NULL,
    -- `seq` of the last change that added an emoji. Taking one back is a change too, but it
    -- is nothing new for the message's author to see (the chat list's heart badge).
    added_seq BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX message_reactions_conversation_seq_idx
    ON message_reactions (conversation_id, seq);

-- The unseen count: live reactions by the other person to one person's messages, newest adds
-- last — a range that ends at what they have not seen, however long the chat's history is.
CREATE INDEX message_reactions_unseen_idx
    ON message_reactions (conversation_id, message_sender_id, added_seq)
    WHERE ciphertext IS NOT NULL AND user_id <> message_sender_id;

-- Account deletion cascades on user_id; removals keep their rows, so the table only grows.
CREATE INDEX message_reactions_user_idx ON message_reactions (user_id);

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

-- How far each participant has seen reactions to their own messages: an emoji the other
-- participant added after `seen_seq` counts as unseen (the chat list's heart badge).
CREATE TABLE reaction_reads (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    seen_seq BIGINT NOT NULL,
    PRIMARY KEY (user_id, conversation_id)
);

-- Deleting a conversation cascades here.
CREATE INDEX reaction_reads_conversation_idx ON reaction_reads (conversation_id);
