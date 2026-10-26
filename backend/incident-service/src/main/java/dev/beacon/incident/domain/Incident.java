package dev.beacon.incident.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The incident aggregate. Status only changes through {@link #acknowledge} and {@link #resolve},
 * which enforce the {@link IncidentStatus} state machine and return the event that happened.
 */
@Entity
@Table(name = "incidents")
public class Incident {

    /** Assigned here rather than by the database, so the Triggered event can carry it before the first save. */
    @Id
    private UUID id;

    /**
     * Optimistic lock. Hibernate adds {@code WHERE version = ?} to every update and fails the
     * transaction if another one changed the row first. A wrapper type, so that Spring Data treats
     * {@code null} as "not persisted yet" and inserts instead of merging.
     */
    @Version
    private Long version;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IncidentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** The monitoring system that opened this incident; null when a person triggered it. */
    @Column(length = 48)
    private String source;

    /** Alerts with this fingerprint fold into this incident while it is open. */
    @Column(length = 128)
    private String fingerprint;

    /**
     * Changed only by {@link IncidentRepository#recordRepeatAlert}, a bulk update that does not bump
     * {@link #version}: a noisy alert must not make every responder's acknowledge stale.
     */
    @Column(name = "alert_count", nullable = false)
    private int alertCount;

    @Column(name = "last_alert_at")
    private Instant lastAlertAt;

    protected Incident() {
        // required by JPA
    }

    private Incident(String title, String description, Severity severity, Instant now) {
        this.id = UUID.randomUUID();
        this.title = title;
        this.description = description;
        this.severity = severity;
        this.status = IncidentStatus.TRIGGERED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Every incident starts life in {@link IncidentStatus#TRIGGERED}. */
    public static Incident trigger(String title, String description, Severity severity, Instant now) {
        return new Incident(title, description, severity, now);
    }

    /** An incident opened by the first alert with a given fingerprint. */
    public static Incident triggerFromAlert(String title, String description, Severity severity,
                                           String source, String fingerprint, Instant alertReceivedAt, Instant now) {
        Incident incident = new Incident(title, description, severity, now);
        incident.source = source;
        incident.fingerprint = fingerprint;
        incident.alertCount = 1;
        incident.lastAlertAt = alertReceivedAt;
        return incident;
    }

    public IncidentEvent.Triggered triggeredBy(String actor) {
        return new IncidentEvent.Triggered(id, actor, createdAt, severity);
    }

    public IncidentEvent.Acknowledged acknowledge(String actor, Instant now) {
        moveTo(IncidentStatus.ACKNOWLEDGED, now);
        acknowledgedAt = now;
        return new IncidentEvent.Acknowledged(id, actor, now);
    }

    public IncidentEvent.Resolved resolve(String actor, String resolutionNote, Instant now) {
        moveTo(IncidentStatus.RESOLVED, now);
        resolvedAt = now;
        return new IncidentEvent.Resolved(id, actor, now, resolutionNote);
    }

    /**
     * Rejects a command that was based on an outdated copy of this incident. The client sends the
     * version it last saw; this is checked before the state machine, so a stale client is always told
     * to reload rather than given a confusing transition error.
     */
    public void requireVersion(long expectedVersion) {
        if (version == null || version != expectedVersion) {
            throw new StaleIncidentException(id, expectedVersion, version == null ? 0 : version);
        }
    }

    /** Postmortems and their attachments are only for incidents that are over. */
    public void requireResolved() {
        if (status != IncidentStatus.RESOLVED) {
            throw new IncidentNotResolvedException(id, status);
        }
    }

    private void moveTo(IncidentStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalTransitionException(id, status, next);
        }
        status = next;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Severity getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getSource() {
        return source;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getAlertCount() {
        return alertCount;
    }

    public Instant getLastAlertAt() {
        return lastAlertAt;
    }
}
