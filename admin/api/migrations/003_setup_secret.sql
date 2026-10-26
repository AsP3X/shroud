-- The authenticator secret a setup link shows, kept with the link until setup completes. It used
-- to be stored on the operator as soon as the link was opened, so whoever had seen the QR code
-- held an enrolled operator's second factor before any password existed (security audit M1).
-- Sealed with ADMIN_SECRET_KEY like operators.totp_secret_enc; cleared once the link is used.

ALTER TABLE admin.setup_links ADD COLUMN totp_secret_enc BYTEA NULL;
