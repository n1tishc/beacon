package dev.beacon.incident.domain;

import java.util.UUID;

/**
 * The caller acted on an outdated copy of the incident: someone else changed it in the meantime.
 * Mapped to 409 by {@code ApiExceptionHandler}; the client should reload and decide again.
 */
public class StaleIncidentException extends RuntimeException {

    public StaleIncidentException(UUID id, long expectedVersion, long currentVersion) {
        super("Incident " + id + " was changed by someone else (you had version " + expectedVersion
                + ", it is now " + currentVersion + "). Reload it and try again.");
    }
}
