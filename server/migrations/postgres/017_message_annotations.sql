-- Annotations: sealed messages that attach data to an earlier message instead of being
-- shown as a bubble. First use: a voice transcript made by the recipient, shared back so
-- the sender's devices can show it too.
-- Agent: DB forward-only; ciphertext only. The server never bumps conversation order or
-- sends a push for an annotation (see routes/messages.rs).

ALTER TABLE messages DROP CONSTRAINT messages_content_type_check;

ALTER TABLE messages
    ADD CONSTRAINT messages_content_type_check
    CHECK (content_type IN ('text', 'media', 'annotation'));
