-- APNs device tokens per device (opaque data pushes only).
-- Agent: DB forward-only; never store message content in push payloads.

CREATE TABLE push_tokens (
    device_id UUID PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    apns_token TEXT NOT NULL,
    environment TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT push_tokens_environment_check
        CHECK (environment IN ('sandbox', 'production'))
);

CREATE INDEX push_tokens_apns_token_idx ON push_tokens (apns_token);
