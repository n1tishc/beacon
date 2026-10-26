package dev.beacon.incident.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The persisted form of an {@link IncidentEvent}: one append-only row per event. */
@Entity
@Table(name = "timeline_entries")
public class TimelineEntry {

    /** A database sequence, so entries with the same timestamp still have a stable order. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private TimelineEntryType type;

    @Column(nullable = false, updatable = false, length = 64)
    private String actor;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(updatable = false, length = 4000)
    private String note;

    protected TimelineEntry() {
        // required by JPA
    }

    private TimelineEntry(IncidentEvent event, TimelineEntryType type, String note) {
        this.incidentId = event.incidentId();
        this.type = type;
        this.actor = event.actor();
        this.occurredAt = event.occurredAt();
        this.note = note;
    }

    public static TimelineEntry of(IncidentEvent event) {
        return switch (event) {
            case IncidentEvent.Triggered triggered ->
                    new TimelineEntry(triggered, TimelineEntryType.TRIGGERED, "Severity " + triggered.severity());
            case IncidentEvent.Acknowledged acknowledged ->
                    new TimelineEntry(acknowledged, TimelineEntryType.ACKNOWLEDGED, null);
            case IncidentEvent.Resolved resolved ->
                    new TimelineEntry(resolved, TimelineEntryType.RESOLVED, resolved.resolutionNote());
            case IncidentEvent.NoteAdded noteAdded ->
                    new TimelineEntry(noteAdded, TimelineEntryType.NOTE, noteAdded.text());
        };
    }

    public Long getId() {
        return id;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public TimelineEntryType getType() {
        return type;
    }

    public String getActor() {
        return actor;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getNote() {
        return note;
    }
}
