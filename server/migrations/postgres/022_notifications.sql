-- Notifications: per-device settings, per-chat mutes, unread markers, Web Push, split APNs tokens.
-- Human: The server decides which of a user's devices get a push, so it has to know what each
-- device asked for (settings), which chats its owner muted, and how many messages are still
-- unread (the badge). None of it is message content: a mute and a read marker are the same kind
-- of metadata as read receipts, and push payloads still carry no plaintext.
-- Agent: DB forward-only. Read markers start at each chat's latest message, so nothing counts as
-- unread on the first list after this migration.

-- One device held two APNs tokens (alerts, and PushKit VoIP with a `voip:` prefix) in one row,
-- so each upload replaced the other. Now one row per kind; the prefix becomes the kind.
ALTER TABLE push_tokens ADD COLUMN kind TEXT NOT NULL DEFAULT 'alert';
UPDATE push_tokens
SET kind = 'voip', apns_token = substring(apns_token FROM 6)
WHERE apns_token LIKE 'voip:%';
ALTER TABLE push_tokens ALTER COLUMN kind DROP DEFAULT;
ALTER TABLE push_tokens DROP CONSTRAINT push_tokens_pkey;
ALTER TABLE push_tokens ADD CONSTRAINT push_tokens_pkey PRIMARY KEY (device_id, kind);
ALTER TABLE push_tokens
    ADD CONSTRAINT push_tokens_kind_check CHECK (kind IN ('alert', 'voip'));
-- AES-256-GCM key the iPhone's notification extension opens the sender's name with. It keeps
-- the name away from Apple, which relays the push; it is not an end-to-end key (the server
-- chose what went in).
ALTER TABLE push_tokens ADD COLUMN payload_key BYTEA NULL;
ALTER TABLE push_tokens
    ADD CONSTRAINT push_tokens_payload_key_len
    CHECK (payload_key IS NULL OR length(payload_key) = 32);

-- A browser's push subscription (RFC 8030). The push service only sees RFC 8291 ciphertext.
CREATE TABLE web_push_subscriptions (
    device_id UUID PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL,
    -- The browser's P-256 key (uncompressed point) and auth secret, from PushSubscription.
    p256dh BYTEA NOT NULL,
    auth BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT web_push_subscriptions_p256dh_len CHECK (length(p256dh) = 65),
    CONSTRAINT web_push_subscriptions_auth_len CHECK (length(auth) = 16)
);

-- One browser profile has one endpoint; registering it on another device moves it there.
CREATE UNIQUE INDEX web_push_subscriptions_endpoint_idx ON web_push_subscriptions (endpoint);

-- What a device wants pushed. No row means the defaults below.
CREATE TABLE device_notification_settings (
    device_id UUID PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    -- Name the sender (and the requester of a contact request) in the notification.
    show_sender BOOLEAN NOT NULL DEFAULT true,
    reactions BOOLEAN NOT NULL DEFAULT true,
    contact_requests BOOLEAN NOT NULL DEFAULT true,
    -- APNs sound: `default`, `none`, or a sound bundled with the app.
    sound TEXT NOT NULL DEFAULT 'default',
    -- Put the unread count on the app icon.
    badge BOOLEAN NOT NULL DEFAULT true,
    badge_includes_muted BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT device_notification_settings_sound_check CHECK (sound ~ '^[a-z0-9_-]{1,32}$')
);

-- Chats a user silenced, on all of their devices. NULL `muted_until` = until unmuted; a
-- time in the past is the same as no row.
CREATE TABLE chat_mutes (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    peer_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    muted_until TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, peer_user_id),
    CONSTRAINT chat_mutes_not_self CHECK (user_id <> peer_user_id)
);

-- Account deletion clears the rows that name the deleted user as the peer.
CREATE INDEX chat_mutes_peer_idx ON chat_mutes (peer_user_id);

-- How far each participant has read a chat: the peer's messages created after `read_at` are
-- unread. Reading moves it forward, and so does writing (a reply means the chat was read).
-- Human: A marker rather than counting `message_reads` rows: counting walks only the messages
-- after it, and it works for chats whose receipts cannot be sent (a removed contact).
CREATE TABLE conversation_reads (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, conversation_id)
);

CREATE INDEX conversation_reads_conversation_idx ON conversation_reads (conversation_id);

INSERT INTO conversation_reads (user_id, conversation_id, read_at)
SELECT participant.user_id, c.id, COALESCE(c.last_message_at, c.created_at)
FROM conversations c
CROSS JOIN LATERAL (VALUES (c.user_a_id), (c.user_b_id)) AS participant (user_id)
WHERE c.user_a_id <> c.user_b_id
ON CONFLICT DO NOTHING;

-- Keys the server generates once and keeps: the Web Push (VAPID) signing key.
CREATE TABLE server_keys (
    name TEXT PRIMARY KEY,
    secret BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
