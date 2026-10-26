package dev.beacon.incident.domain;

import java.util.UUID;

/** The state machine does not allow this move, e.g. acknowledging a resolved incident. Mapped to 409. */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(UUID id, IncidentStatus from, IncidentStatus to) {
        super("Incident " + id + " is " + from + " and cannot move to " + to + ".");
    }
}
