-- Initial schema bootstrap for local development.
-- Agent: DB forward-only migration; stores account metadata only — no message plaintext or keys.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX users_username_idx ON users (username);
