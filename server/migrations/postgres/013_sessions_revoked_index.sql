-- Speed purge of old revoked sessions (30-day retention).
-- Agent: DB forward-only; token_hash only.

CREATE INDEX IF NOT EXISTS sessions_revoked_at_idx
    ON sessions (revoked_at)
    WHERE revoked_at IS NOT NULL;
