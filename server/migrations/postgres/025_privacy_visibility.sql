-- What contacts may see of a user's activity: read receipts, typing, online / last seen.
-- Human: Each switch works both ways, as in Signal and WhatsApp. Turning read receipts off
-- means contacts never learn when you read their messages, and you stop seeing when they
-- read yours. The server enforces both directions, because a client could simply ignore a
-- one-sided rule (docs/privacy-options.md, phase 2).
-- Agent: DB forward-only. READ by routes::privacy::both_allow; defaults keep today's behaviour.

ALTER TABLE users
    ADD COLUMN send_read_receipts BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN send_typing BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN share_presence BOOLEAN NOT NULL DEFAULT true;
