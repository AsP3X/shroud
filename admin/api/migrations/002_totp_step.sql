-- The last TOTP time step an operator's code was accepted for. A code is accepted only for a
-- later step, so one seen over a shoulder or phished can't be used again, and the code that
-- signed in can't also pass the fresh-code step-up.

ALTER TABLE admin.operators ADD COLUMN last_totp_step BIGINT NULL;
