package dev.beacon.incident.domain;

import java.util.UUID;

/**
 * Someone else saved the postmortem since the caller loaded it. Mapped to 409; unlike a stale
 * incident, the client keeps the user's draft, because a postmortem can be a lot of typing.
 */
public class StalePostmortemException extends RuntimeException {

    public StalePostmortemException(UUID incidentId, long expectedVersion, long currentVersion) {
        super("The postmortem for incident " + incidentId + " was saved by someone else (you had version "
                + expectedVersion + ", it is now " + currentVersion + ").");
    }

    /** The caller wrote a first draft while someone else did too, or two saves raced in the database. */
    public StalePostmortemException(UUID incidentId) {
        super("Someone else saved the postmortem for incident " + incidentId + " since you loaded it.");
    }
}
