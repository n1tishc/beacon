package dev.beacon.incident.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened to an incident. Domain methods on {@link Incident} return these; the
 * service records each one on the incident's timeline.
 *
 * <p>The interface is sealed, so a {@code switch} over an event is checked for exhaustiveness by
 * the compiler: adding a new event type is a compile error everywhere it is not yet handled.
 */
public sealed interface IncidentEvent {

    UUID incidentId();

    /** Username of whoever caused the event. */
    String actor();

    Instant occurredAt();

    record Triggered(UUID incidentId, String actor, Instant occurredAt, Severity severity) implements IncidentEvent {
    }

    record Acknowledged(UUID incidentId, String actor, Instant occurredAt) implements IncidentEvent {
    }

    record Resolved(UUID incidentId, String actor, Instant occurredAt, String resolutionNote) implements IncidentEvent {
    }

    record NoteAdded(UUID incidentId, String actor, Instant occurredAt, String text) implements IncidentEvent {
    }
}
