package dev.beacon.incident.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentNotFoundException;
import dev.beacon.incident.domain.IncidentRepository;
import dev.beacon.incident.domain.Severity;

@Service
@Transactional(readOnly = true)
public class IncidentService {

    static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final IncidentRepository incidents;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, Clock clock) {
        this.incidents = incidents;
        this.clock = clock;
    }

    @Transactional
    public Incident create(String title, String description, Severity severity) {
        return incidents.save(Incident.trigger(title, description, severity, now()));
    }

    public Page<Incident> list(int page, int size) {
        return incidents.findAll(PageRequest.of(page, size, NEWEST_FIRST));
    }

    public Incident get(UUID id) {
        return incidents.findById(id).orElseThrow(() -> new IncidentNotFoundException(id));
    }

    /**
     * Postgres stores timestamps with microsecond precision. Truncating up front means the
     * response to a create matches what a later read returns.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
