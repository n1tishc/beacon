package dev.beacon.notification.api;

import java.time.Instant;
import java.util.UUID;

import dev.beacon.notification.domain.Channel;
import dev.beacon.notification.domain.EventType;
import dev.beacon.notification.domain.Notification;
import dev.beacon.notification.domain.NotificationStatus;

public record NotificationResponse(UUID id, UUID incidentId, EventType eventType, Channel channel,
                                   NotificationStatus status, int attempts, String detail, String message,
                                   Instant createdAt, Instant updatedAt) {

    static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getIncidentId(),
                notification.getEventType(), notification.getChannel(), notification.getStatus(),
                notification.getAttempts(), notification.getDetail(), notification.getMessage(),
                notification.getCreatedAt(), notification.getUpdatedAt());
    }
}
