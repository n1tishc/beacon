package dev.beacon.notification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What incident-service reports after an incident changed.
 *
 * @param eventId incident-service's id for the event (its timeline entry id); a repeated request
 *                with the same id is recognised and never notifies twice
 */
public record IncidentEvent(long eventId, UUID incidentId, EventType type, String title, String severity,
                            String actor, Instant occurredAt) {
}
