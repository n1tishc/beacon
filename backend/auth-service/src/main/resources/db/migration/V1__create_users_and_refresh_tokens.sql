CREATE TABLE users (
    id            UUID PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    display_name  VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL CHECK (role IN ('ADMIN', 'RESPONDER', 'VIEWER')),
    created_at    TIMESTAMPTZ  NOT NULL
);

-- Only a SHA-256 hash of each refresh token is stored, so a database leak does not leak sessions.
CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

-- Reuse detection revokes every active token of a user.
CREATE INDEX idx_refresh_tokens_active_by_user ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
