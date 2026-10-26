package dev.beacon.notification.domain;

/** Declared alphabetically, so Java's enum order and the database's string order agree. */
public enum Channel {
    /** Records the message and always succeeds. Stands in for Slack where no webhook is configured. */
    MOCK,
    SLACK
}
