package dev.beacon.incident.domain;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** Rendered by Spring MVC as an RFC 7807 problem response with status 404. */
public class IncidentNotFoundException extends ErrorResponseException {

    public IncidentNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND);
        setTitle("Incident not found");
        setDetail("No incident exists with id " + id);
    }
}
