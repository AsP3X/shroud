-- User-level read receipts (milestone 6). Delivery remains per-device in message_deliveries.
-- Agent: DB forward-only; no plaintext.

CREATE TABLE message_reads (
    message_id UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX message_reads_user_id_idx ON message_reads (user_id);
CREATE INDEX message_reads_message_id_idx ON message_reads (message_id);
