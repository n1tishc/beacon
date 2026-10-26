-- One row per message per channel. Rows are written as PENDING before delivery starts, so a
-- delivery that never finishes (the service died mid-call) stays visible instead of vanishing.
CREATE TABLE notifications (
    id          UUID PRIMARY KEY,
    event_id    BIGINT       NOT NULL,
    incident_id UUID         NOT NULL,
    event_type  VARCHAR(16)  NOT NULL CHECK (event_type IN ('TRIGGERED', 'ACKNOWLEDGED', 'RESOLVED')),
    channel     VARCHAR(16)  NOT NULL CHECK (channel IN ('SLACK', 'MOCK')),
    message     TEXT         NOT NULL,
    status      VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    attempts    INTEGER      NOT NULL CHECK (attempts >= 0),
    detail      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    -- Idempotency: incident-service retries a request whose response it did not get. The retry
    -- finds these rows instead of posting to Slack a second time.
    CONSTRAINT uq_notifications_event_channel UNIQUE (event_id, channel)
);

CREATE INDEX idx_notifications_incident ON notifications (incident_id, created_at);
