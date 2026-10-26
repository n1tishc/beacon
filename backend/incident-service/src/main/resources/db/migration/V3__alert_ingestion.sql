-- Incidents opened by monitoring alerts remember where they came from and how often they fired.
ALTER TABLE incidents
    ADD COLUMN source        VARCHAR(48),
    ADD COLUMN fingerprint   VARCHAR(128),
    ADD COLUMN alert_count   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_alert_at TIMESTAMPTZ;

-- Deduplication: at most one open incident per fingerprint. Two alerts racing to open the same
-- incident cannot both succeed; the loser retries and folds into the winner's incident.
-- (Manual incidents have no fingerprint, and NULLs never collide.)
CREATE UNIQUE INDEX uq_incidents_open_fingerprint ON incidents (fingerprint) WHERE status <> 'RESOLVED';

-- Every alert received, keyed by the id the ingest Lambda assigned. SQS delivers at least once;
-- a redelivered message hits this primary key and is recognised as already processed.
CREATE TABLE alerts (
    id          UUID PRIMARY KEY,
    incident_id UUID         NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    source      VARCHAR(48)  NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    title       VARCHAR(200) NOT NULL,
    severity    VARCHAR(16)  NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    received_at TIMESTAMPTZ  NOT NULL,
    ingested_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_alerts_incident ON alerts (incident_id, received_at);
