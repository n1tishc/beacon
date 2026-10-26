-- At most one postmortem per incident, written once the incident is resolved. Its own version
-- column, so two people editing the same postmortem cannot silently overwrite each other.
CREATE TABLE postmortems (
    incident_id  UUID PRIMARY KEY REFERENCES incidents (id) ON DELETE CASCADE,
    version      BIGINT        NOT NULL,
    summary      VARCHAR(4000) NOT NULL,
    impact       VARCHAR(4000),
    root_cause   VARCHAR(4000),
    action_items VARCHAR(4000),
    created_by   VARCHAR(64)   NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_by   VARCHAR(64)   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL
);

-- Files attached to an incident's postmortem. The bytes live in S3 under object_key, which the
-- server chooses; file_name is only what the uploader called it. A row starts PENDING when an upload
-- URL is issued and becomes UPLOADED once the server has checked the object that arrived in S3.
CREATE TABLE attachments (
    id           UUID PRIMARY KEY,
    incident_id  UUID         NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    object_key   VARCHAR(200) NOT NULL UNIQUE,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL CHECK (size_bytes > 0),
    status       VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'UPLOADED')),
    uploaded_by  VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    uploaded_at  TIMESTAMPTZ,
    CONSTRAINT uploaded_attachments_have_a_time CHECK (status <> 'UPLOADED' OR uploaded_at IS NOT NULL)
);

CREATE INDEX idx_attachments_incident ON attachments (incident_id, created_at);
