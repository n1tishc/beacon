package dev.beacon.incident.notify;

import java.time.Instant;
import java.util.UUID;

import dev.beacon.incident.service.IncidentChanged;

/** The body of {@code POST /internal/notifications}; notification-service's IncidentEventRequest mirrors it. */
public record NotificationRequest(long eventId, UUID incidentId, String type, String title, String severity,
                                  String actor, Instant occurredAt) {

    static NotificationRequest from(IncidentChanged change) {
        return new NotificationRequest(change.eventId(), change.incidentId(), change.type().name(), change.title(),
                change.severity().name(), change.actor(), change.occurredAt());
    }
}
