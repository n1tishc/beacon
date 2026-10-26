package dev.beacon.incident.domain;

/**
 * The incident lifecycle as an explicit state machine:
 *
 * <pre>
 * TRIGGERED ──acknowledge──▶ ACKNOWLEDGED ──resolve──▶ RESOLVED
 *     └───────────────────resolve──────────────────────▲
 * </pre>
 *
 * Resolving straight from TRIGGERED is allowed: an alert that clears on its own (or a false
 * positive) should not need a ceremonial acknowledge first. RESOLVED is terminal; a recurrence
 * opens a new incident.
 */
public enum IncidentStatus {
    TRIGGERED,
    ACKNOWLEDGED,
    RESOLVED;

    public boolean canTransitionTo(IncidentStatus next) {
        return switch (this) {
            case TRIGGERED -> next == ACKNOWLEDGED || next == RESOLVED;
            case ACKNOWLEDGED -> next == RESOLVED;
            case RESOLVED -> false;
        };
    }
}
