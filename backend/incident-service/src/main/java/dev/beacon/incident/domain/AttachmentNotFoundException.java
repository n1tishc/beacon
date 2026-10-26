package dev.beacon.incident.domain;

import java.util.UUID;

/** Mapped to 404 by {@code ApiExceptionHandler}. */
public class AttachmentNotFoundException extends RuntimeException {

    public AttachmentNotFoundException(UUID incidentId, UUID attachmentId) {
        super("Incident " + incidentId + " has no attachment with id " + attachmentId);
    }
}
