package dev.beacon.incident.api;

import java.time.Instant;
import java.util.UUID;

import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentStatus;
import dev.beacon.incident.domain.Severity;

/**
 * {@code version} must be sent back with acknowledge/resolve; see {@link AcknowledgeRequest}.
 * {@code source}, {@code fingerprint} and the alert fields are null/0 for incidents a person triggered.
 */
public record IncidentResponse(
        UUID id,
        long version,
        String title,
        String description,
        Severity severity,
        IncidentStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        String source,
        String fingerprint,
        int alertCount,
        Instant lastAlertAt) {

    public static IncidentResponse from(Incident incident) {
        return new IncidentResponse(
                incident.getId(),
                incident.getVersion(),
                incident.getTitle(),
                incident.getDescription(),
                incident.getSeverity(),
                incident.getStatus(),
                incident.getCreatedAt(),
                incident.getUpdatedAt(),
                incident.getAcknowledgedAt(),
                incident.getResolvedAt(),
                incident.getSource(),
                incident.getFingerprint(),
                incident.getAlertCount(),
                incident.getLastAlertAt());
    }
}
