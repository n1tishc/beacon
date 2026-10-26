package dev.beacon.incident.api;

import java.time.Instant;
import java.util.UUID;

import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentStatus;
import dev.beacon.incident.domain.Severity;

public record IncidentResponse(
        UUID id,
        String title,
        String description,
        Severity severity,
        IncidentStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static IncidentResponse from(Incident incident) {
        return new IncidentResponse(
                incident.getId(),
                incident.getTitle(),
                incident.getDescription(),
                incident.getSeverity(),
                incident.getStatus(),
                incident.getCreatedAt(),
                incident.getUpdatedAt());
    }
}
