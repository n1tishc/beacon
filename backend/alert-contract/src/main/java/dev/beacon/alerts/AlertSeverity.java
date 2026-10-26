package dev.beacon.alerts;

/** Same names as incident-service's {@code Severity}, so an alert maps onto an incident one to one. */
public enum AlertSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
