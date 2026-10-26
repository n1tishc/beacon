package dev.beacon.incident.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * {@code version} is the incident version the caller last saw. If the incident has changed since,
 * the command is rejected with 409 instead of silently acting on a state the caller never saw.
 */
public record AcknowledgeRequest(@NotNull @PositiveOrZero Long version) {
}
