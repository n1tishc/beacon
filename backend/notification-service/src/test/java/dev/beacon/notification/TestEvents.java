package dev.beacon.notification;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Request bodies shaped exactly like the ones incident-service sends. */
public final class TestEvents {

    private static final AtomicLong nextEventId = new AtomicLong(1);

    private TestEvents() {
    }

    public static long newEventId() {
        return nextEventId.getAndIncrement();
    }

    public static String json(long eventId, UUID incidentId, String type) {
        return """
                {"eventId": %d, "incidentId": "%s", "type": "%s", "title": "Checkout is down",
                 "severity": "HIGH", "actor": "alert:datadog", "occurredAt": "2026-10-25T12:00:00Z"}
                """.formatted(eventId, incidentId, type);
    }

    public static String triggered() {
        return json(newEventId(), UUID.randomUUID(), "TRIGGERED");
    }
}
