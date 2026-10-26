package dev.beacon.incident.domain;

import java.util.UUID;

/** Mapped to 404 by {@code ApiExceptionHandler}. */
public class IncidentNotFoundException extends RuntimeException {

    public IncidentNotFoundException(UUID id) {
        super("No incident exists with id " + id);
    }
}
