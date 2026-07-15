-- Encrypted media object metadata (bytes live in Nebular or stub).
-- Agent: DB forward-only; no plaintext media bytes in Postgres.

CREATE TABLE media_objects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    uploader_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    uploader_device_id UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    bucket TEXT NOT NULL,
    object_key TEXT NOT NULL,
    size_bytes BIGINT NULL,
    content_type TEXT NULL,
    message_id UUID NULL REFERENCES messages (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT media_objects_bucket_key_unique UNIQUE (bucket, object_key),
    CONSTRAINT media_objects_size_positive CHECK (size_bytes IS NULL OR size_bytes > 0)
);

CREATE INDEX media_objects_uploader_idx ON media_objects (uploader_user_id);
CREATE INDEX media_objects_message_id_idx ON media_objects (message_id);

ALTER TABLE messages
    ADD COLUMN media_object_id UUID NULL REFERENCES media_objects (id) ON DELETE SET NULL;

CREATE INDEX messages_media_object_id_idx ON messages (media_object_id);
