CREATE TABLE incidents (
    id          UUID PRIMARY KEY,
    title       VARCHAR(200)  NOT NULL,
    description VARCHAR(4000),
    severity    VARCHAR(16)   NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    status      VARCHAR(16)   NOT NULL CHECK (status IN ('TRIGGERED', 'ACKNOWLEDGED', 'RESOLVED')),
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL
);

-- The dashboard lists newest incidents first.
CREATE INDEX idx_incidents_created_at ON incidents (created_at DESC);
