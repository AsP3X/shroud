-- Drop contact edges between two users when either has blocked the other.
-- Human: A block that raced an accept could commit after the accept wrote the edges and miss
-- them, leaving a blocked pair as contacts (presence, typing and key fetch only check contacts).
-- `POST /blocks` now cancels pending requests before it deletes the edges, so this only clears
-- what the race left behind.
-- Agent: DB forward-only. Data only; routes::blocks::create_block keeps it true from here on.

DELETE FROM contacts c
USING blocks b
WHERE (b.blocker_id = c.user_id AND b.blocked_id = c.contact_user_id)
   OR (b.blocker_id = c.contact_user_id AND b.blocked_id = c.user_id);
