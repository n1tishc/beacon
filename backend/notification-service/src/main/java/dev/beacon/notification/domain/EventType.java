package dev.beacon.notification.domain;

/** The incident events worth telling people about. Notes and repeat alerts are not among them. */
public enum EventType {
    TRIGGERED,
    ACKNOWLEDGED,
    RESOLVED
}
