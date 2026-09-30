-- The last media state each device in a call sent, so the other device can catch up.
-- Human: Either person can turn their camera on or off during a call, which switches it between
-- voice and video (docs/calls.md, "Switching between voice and video"). The change travels as a
-- sealed `media_state` signal. A device whose socket was down at that moment kept showing the
-- old picture: a frozen face, or an avatar over live video. The server now keeps each device's
-- latest one, still sealed, and hands it to the other device in the call on `GET /calls/{id}`
-- and on the heartbeat. Both are cleared when the call ends.
-- Agent: DB forward-only. Opaque `c1.` payloads the server cannot read; NULL until that device
-- sends one, and again once the call is over.

ALTER TABLE calls ADD COLUMN caller_media_state TEXT NULL;
ALTER TABLE calls ADD COLUMN callee_media_state TEXT NULL;
