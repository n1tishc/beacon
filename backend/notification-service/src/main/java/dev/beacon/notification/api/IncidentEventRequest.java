package dev.beacon.notification.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import dev.beacon.notification.domain.EventType;
import dev.beacon.notification.domain.IncidentEvent;

/** What incident-service posts. Field names and types must match its {@code NotificationRequest}. */
public record IncidentEventRequest(
        @NotNull @Positive Long eventId,
        @NotNull UUID incidentId,
        @NotNull EventType type,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 16) String severity,
        @NotBlank @Size(max = 100) String actor,
        @NotNull Instant occurredAt) {

    IncidentEvent toEvent() {
        return new IncidentEvent(eventId, incidentId, type, title, severity, actor, occurredAt);
    }
}
