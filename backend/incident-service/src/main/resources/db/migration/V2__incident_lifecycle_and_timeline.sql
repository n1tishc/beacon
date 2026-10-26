-- Optimistic locking plus the timestamps of each lifecycle step.
ALTER TABLE incidents
    ADD COLUMN version         BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN acknowledged_at TIMESTAMPTZ,
    ADD COLUMN resolved_at     TIMESTAMPTZ;

-- The database backs up the state machine: a status always has the timestamp that goes with it.
ALTER TABLE incidents
    ADD CONSTRAINT chk_incidents_acknowledged_at
        CHECK (status <> 'ACKNOWLEDGED' OR acknowledged_at IS NOT NULL),
    ADD CONSTRAINT chk_incidents_resolved_at
        CHECK (status <> 'RESOLVED' OR resolved_at IS NOT NULL);

-- Append-only history of everything that happened to an incident.
CREATE TABLE timeline_entries (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id UUID          NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    type        VARCHAR(16)   NOT NULL CHECK (type IN ('TRIGGERED', 'ACKNOWLEDGED', 'RESOLVED', 'NOTE')),
    actor       VARCHAR(64)   NOT NULL,
    occurred_at TIMESTAMPTZ   NOT NULL,
    note        VARCHAR(4000)
);

CREATE INDEX idx_timeline_entries_incident ON timeline_entries (incident_id, occurred_at, id);

-- Incidents created before this migration get the Triggered entry they would have had.
-- Who created them was never recorded.
INSERT INTO timeline_entries (incident_id, type, actor, occurred_at, note)
SELECT id, 'TRIGGERED', 'unknown', created_at, 'Severity ' || severity
FROM incidents;
