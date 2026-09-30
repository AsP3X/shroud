-- Calls end when a device in them goes quiet, and say which protocol placed them.
-- Human: A call stayed `active` forever when an app crashed or lost its network mid-call, and
-- both people were "busy" for good. The two devices in a call now report in (heartbeat or
-- signal) every few seconds; a call not heard from for 45 s is ended by the server
-- (routes/calls.rs). Protocol 2 negotiates media only after the answer, over sealed signals.
-- Agent: DB forward-only. seen_at columns are NULL until the first report; readers fall back to
-- created_at / answered_at.

ALTER TABLE calls ADD COLUMN caller_seen_at TIMESTAMPTZ NULL;
ALTER TABLE calls ADD COLUMN callee_seen_at TIMESTAMPTZ NULL;
ALTER TABLE calls ADD COLUMN protocol SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE calls ADD CONSTRAINT calls_protocol_check CHECK (protocol IN (1, 2));

-- Call history (`GET /calls`) reads each person's calls newest first.
CREATE INDEX calls_caller_created_idx ON calls (caller_user_id, created_at DESC);
CREATE INDEX calls_callee_created_idx ON calls (callee_user_id, created_at DESC);
DROP INDEX IF EXISTS calls_caller_user_id_idx;
DROP INDEX IF EXISTS calls_callee_user_id_idx;
