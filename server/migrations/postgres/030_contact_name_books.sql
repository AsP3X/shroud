-- One sealed blob per account: the contact names its devices have opened, so a new or
-- emptied device (a browser keeps no contact list) learns them from the account's other
-- devices. AES-256-GCM under a key from the phrase's history key; the server cannot read it.
-- `version` lets two devices merge instead of overwriting each other (compare-and-swap).
CREATE TABLE contact_name_books (
    user_id UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    sealed TEXT NOT NULL,
    version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
