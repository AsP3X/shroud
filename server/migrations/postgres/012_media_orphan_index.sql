-- Speed orphan GC: unlinked media by age.
-- Agent: DB forward-only; no plaintext.

CREATE INDEX IF NOT EXISTS media_objects_orphan_created_idx
    ON media_objects (created_at)
    WHERE message_id IS NULL;
