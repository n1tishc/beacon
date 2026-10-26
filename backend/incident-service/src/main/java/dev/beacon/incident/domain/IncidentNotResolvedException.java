package dev.beacon.incident.domain;

import java.util.UUID;

/** Postmortems and their attachments wait until the incident is resolved. Mapped to 409. */
public class IncidentNotResolvedException extends RuntimeException {

    public IncidentNotResolvedException(UUID id, IncidentStatus status) {
        super("Incident " + id + " is " + status + ". Resolve it before writing its postmortem.");
    }
}
