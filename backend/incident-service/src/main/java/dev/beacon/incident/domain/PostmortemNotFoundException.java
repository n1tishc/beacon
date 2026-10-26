package dev.beacon.incident.domain;

import java.util.UUID;

/** Mapped to 404 by {@code ApiExceptionHandler}: nobody has written this incident's postmortem yet. */
public class PostmortemNotFoundException extends RuntimeException {

    public PostmortemNotFoundException(UUID incidentId) {
        super("Incident " + incidentId + " has no postmortem yet.");
    }
}
