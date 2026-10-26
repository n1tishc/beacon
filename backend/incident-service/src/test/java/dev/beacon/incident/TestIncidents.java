package dev.beacon.incident;

import java.time.Instant;

import org.springframework.test.util.ReflectionTestUtils;

import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentEvent;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.domain.TimelineEntry;

/** Incidents for tests that do not touch a database. */
public final class TestIncidents {

    private TestIncidents() {
    }

    public static Incident persisted(String title, Severity severity, Instant createdAt) {
        return persisted(Incident.trigger(title, null, severity, createdAt));
    }

    /** What Hibernate does on the first insert: version starts at 0. */
    public static Incident persisted(Incident incident) {
        return withVersion(incident, 0L);
    }

    public static Incident withVersion(Incident incident, long version) {
        ReflectionTestUtils.setField(incident, "version", version);
        return incident;
    }

    /** A timeline entry as the database returns it, with its generated id. */
    public static TimelineEntry saved(IncidentEvent event, long id) {
        TimelineEntry entry = TimelineEntry.of(event);
        ReflectionTestUtils.setField(entry, "id", id);
        return entry;
    }
}
