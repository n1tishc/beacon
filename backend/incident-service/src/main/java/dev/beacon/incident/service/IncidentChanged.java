package dev.beacon.incident.service;

import java.time.Instant;
import java.util.UUID;

import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.domain.TimelineEntry;
import dev.beacon.incident.domain.TimelineEntryType;

/**
 * Published (as a Spring application event) when an incident changed in a way people are told about.
 * A snapshot of plain values, so a listener on another thread never touches a JPA entity.
 *
 * @param eventId the timeline entry's id: unique per event, so it doubles as an idempotency key
 */
public record IncidentChanged(long eventId, UUID incidentId, TimelineEntryType type, String title,
                              Severity severity, String actor, Instant occurredAt) {

    static IncidentChanged of(Incident incident, TimelineEntry entry) {
        return new IncidentChanged(entry.getId(), incident.getId(), entry.getType(), incident.getTitle(),
                incident.getSeverity(), entry.getActor(), entry.getOccurredAt());
    }
}
