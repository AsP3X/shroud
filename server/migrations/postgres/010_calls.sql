-- 1:1 call signaling state (milestone 9). No media, SDP, or keys stored long-term.
-- Agent: DB forward-only; opaque metadata only.

CREATE TABLE calls (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caller_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    caller_device_id UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    callee_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    callee_device_id UUID NULL REFERENCES devices (id) ON DELETE SET NULL,
    modality TEXT NOT NULL,
    status TEXT NOT NULL,
    ended_reason TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at TIMESTAMPTZ NULL,
    ended_at TIMESTAMPTZ NULL,
    CONSTRAINT calls_modality_check CHECK (modality IN ('voice', 'video')),
    CONSTRAINT calls_status_check CHECK (
        status IN (
            'ringing',
            'active',
            'ended',
            'rejected',
            'busy',
            'missed',
            'cancelled'
        )
    ),
    CONSTRAINT calls_not_self CHECK (caller_user_id <> callee_user_id)
);

CREATE INDEX calls_caller_user_id_idx ON calls (caller_user_id);
CREATE INDEX calls_callee_user_id_idx ON calls (callee_user_id);
CREATE INDEX calls_active_pair_idx ON calls (caller_user_id, callee_user_id)
    WHERE status IN ('ringing', 'active');
