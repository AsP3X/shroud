-- When an upload started publishing its blob to the media store, while it is doing so.
-- Human: `PUT /media/{id}/content` used to hold a row lock, and with it a pooled connection, for
-- the whole publish, which can take up to an hour per Nebular attempt; ten slow publishes took the
-- whole pool (security audit 2026-11 S1). The upload now sets this marker in a statement of its
-- own, publishes with no connection held, and clears it. A send that links the media and the
-- deletes refuse a row whose marker is live, so a linked blob is never replaced and a deleted row
-- never gets a blob nobody collects. A marker older than the longest publish belonged to a process
-- that died mid-publish and counts as unset.
-- Agent: DB forward-only. NULL for every existing row. WRITTEN by routes::media::put_content
-- (mark_publishing / finish_publish); READ by routes::messages::send_message,
-- routes::media::purge_media_ids and purge_orphan_media.

ALTER TABLE media_objects
    ADD COLUMN publishing_at TIMESTAMPTZ;
