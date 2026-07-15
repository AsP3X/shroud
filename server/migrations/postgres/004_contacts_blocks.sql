-- Contacts, contact requests, and blocks (social graph for 1:1 messaging).
-- Agent: DB forward-only; no message plaintext.

CREATE TABLE contact_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    to_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at TIMESTAMPTZ NULL,
    CONSTRAINT contact_requests_not_self CHECK (from_user_id <> to_user_id),
    CONSTRAINT contact_requests_status_check
        CHECK (status IN ('pending', 'accepted', 'rejected', 'cancelled'))
);

CREATE UNIQUE INDEX contact_requests_one_pending
    ON contact_requests (from_user_id, to_user_id)
    WHERE status = 'pending';

CREATE INDEX contact_requests_to_status_idx
    ON contact_requests (to_user_id, status);

CREATE INDEX contact_requests_from_status_idx
    ON contact_requests (from_user_id, status);

CREATE TABLE contacts (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    contact_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, contact_user_id),
    CONSTRAINT contacts_not_self CHECK (user_id <> contact_user_id)
);

CREATE INDEX contacts_contact_user_id_idx ON contacts (contact_user_id);

CREATE TABLE blocks (
    blocker_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT blocks_not_self CHECK (blocker_id <> blocked_id)
);

CREATE INDEX blocks_blocked_id_idx ON blocks (blocked_id);
