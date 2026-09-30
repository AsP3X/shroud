-- Allow Saved Messages / Notes: a conversation where user_a_id = user_b_id (self).
-- Previous constraint required strict ordering user_a < user_b.

ALTER TABLE conversations DROP CONSTRAINT IF EXISTS conversations_ordered_pair;

ALTER TABLE conversations
    ADD CONSTRAINT conversations_ordered_pair CHECK (user_a_id <= user_b_id);
