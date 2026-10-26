package dev.beacon.incident.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * One alert as received, attached to the incident it opened or was folded into. The id comes from
 * the ingest Lambda, which is what makes processing idempotent: see the {@code alerts} migration.
 */
@Entity
@Table(name = "alerts")
public class Alert implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(nullable = false, updatable = false, length = 48)
    private String source;

    @Column(nullable = false, updatable = false, length = 128)
    private String fingerprint;

    @Column(nullable = false, updatable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Severity severity;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "ingested_at", nullable = false, updatable = false)
    private Instant ingestedAt;

    /**
     * The id is assigned up front, so Spring Data cannot tell a new alert from a stored one by a null
     * id. Without this it would SELECT before every insert; with it, a duplicate id fails the insert.
     */
    @Transient
    private boolean isNew = true;

    protected Alert() {
        // required by JPA
    }

    public Alert(UUID id, UUID incidentId, String source, String fingerprint, String title, Severity severity,
                 Instant receivedAt, Instant ingestedAt) {
        this.id = id;
        this.incidentId = incidentId;
        this.source = source;
        this.fingerprint = fingerprint;
        this.title = title;
        this.severity = severity;
        this.receivedAt = receivedAt;
        this.ingestedAt = ingestedAt;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public String getSource() {
        return source;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getTitle() {
        return title;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }
}
