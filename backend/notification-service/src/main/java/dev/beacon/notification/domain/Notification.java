package dev.beacon.notification.domain;

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

/** One message about one incident event on one channel, and how its delivery went. */
@Entity
@Table(name = "notifications")
public class Notification implements Persistable<UUID> {

    /** Matches the column; longer failure descriptions are cut. */
    static final int DETAIL_MAX = 500;

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private long eventId;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 16)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Channel channel;

    @Column(nullable = false, updatable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    /** Why delivery failed or was skipped; null once sent. */
    @Column(length = DETAIL_MAX)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The id is assigned up front; this makes {@code save} insert instead of SELECT-then-insert. */
    @Transient
    private boolean isNew = true;

    protected Notification() {
        // required by JPA
    }

    private Notification(IncidentEvent event, Channel channel, String message, Instant now) {
        this.id = UUID.randomUUID();
        this.eventId = event.eventId();
        this.incidentId = event.incidentId();
        this.eventType = event.type();
        this.channel = channel;
        this.message = message;
        this.status = NotificationStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Notification pending(IncidentEvent event, Channel channel, String message, Instant now) {
        return new Notification(event, channel, message, now);
    }

    public void sent(int attempts, Instant now) {
        finish(NotificationStatus.SENT, attempts, null, now);
    }

    public void failed(int attempts, String reason, Instant now) {
        finish(NotificationStatus.FAILED, attempts, reason, now);
    }

    public void skipped(String reason, Instant now) {
        finish(NotificationStatus.SKIPPED, 0, reason, now);
    }

    private void finish(NotificationStatus status, int attempts, String detail, Instant now) {
        if (this.status != NotificationStatus.PENDING) {
            throw new IllegalStateException("Notification " + id + " is already " + this.status);
        }
        this.status = status;
        this.attempts = attempts;
        this.detail = detail == null || detail.length() <= DETAIL_MAX ? detail : detail.substring(0, DETAIL_MAX);
        this.updatedAt = now;
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

    public long getEventId() {
        return eventId;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getMessage() {
        return message;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
