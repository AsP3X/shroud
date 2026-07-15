-- 1:1 message envelopes and per-device delivery tracking (HTTP relay).
-- Agent: DB forward-only; ciphertext BYTEA only — never plaintext.

CREATE TABLE conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_a_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_b_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT conversations_ordered_pair CHECK (user_a_id < user_b_id),
    CONSTRAINT conversations_pair_unique UNIQUE (user_a_id, user_b_id)
);

CREATE INDEX conversations_user_a_idx ON conversations (user_a_id);
CREATE INDEX conversations_user_b_idx ON conversations (user_b_id);

CREATE TABLE messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    sender_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sender_device_id UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    client_message_id UUID NOT NULL,
    content_type TEXT NOT NULL,
    ciphertext BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT messages_content_type_check CHECK (content_type IN ('text', 'media')),
    CONSTRAINT messages_sender_client_unique UNIQUE (sender_user_id, client_message_id)
);

CREATE INDEX messages_conversation_created_id_idx
    ON messages (conversation_id, created_at DESC, id DESC);

CREATE TABLE message_deliveries (
    message_id UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    delivered_at TIMESTAMPTZ NULL,
    PRIMARY KEY (message_id, device_id)
);

CREATE INDEX message_deliveries_device_id_idx ON message_deliveries (device_id);
