package dev.beacon.notification.channel;

/** How one delivery attempt (retries included) ended. */
public sealed interface DeliveryResult {

    record Sent(int attempts) implements DeliveryResult {
    }

    record Failed(int attempts, String reason) implements DeliveryResult {
    }

    /** The channel was not called at all, because its circuit breaker is open. */
    record Skipped(String reason) implements DeliveryResult {
    }
}
