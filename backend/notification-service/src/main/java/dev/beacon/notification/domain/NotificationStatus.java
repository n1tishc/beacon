package dev.beacon.notification.domain;

public enum NotificationStatus {
    /** Recorded, delivery in progress. */
    PENDING,
    SENT,
    /** Delivery was attempted and did not succeed, retries included. */
    FAILED,
    /** Not attempted: the channel's circuit breaker was open. */
    SKIPPED
}
