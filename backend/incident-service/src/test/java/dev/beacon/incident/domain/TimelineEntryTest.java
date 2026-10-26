package dev.beacon.incident.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TimelineEntryTest {

    private static final UUID INCIDENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Test
    void mapsEachEventTypeToAnEntry() {
        assertEntry(new IncidentEvent.Triggered(INCIDENT, "riley", NOW, Severity.CRITICAL),
                TimelineEntryType.TRIGGERED, "Severity CRITICAL");
        assertEntry(new IncidentEvent.Acknowledged(INCIDENT, "riley", NOW),
                TimelineEntryType.ACKNOWLEDGED, null);
        assertEntry(new IncidentEvent.Resolved(INCIDENT, "riley", NOW, "Rolled back"),
                TimelineEntryType.RESOLVED, "Rolled back");
        assertEntry(new IncidentEvent.NoteAdded(INCIDENT, "riley", NOW, "Paged the DB team"),
                TimelineEntryType.NOTE, "Paged the DB team");
    }

    private static void assertEntry(IncidentEvent event, TimelineEntryType type, String note) {
        TimelineEntry entry = TimelineEntry.of(event);

        assertThat(entry.getIncidentId()).isEqualTo(INCIDENT);
        assertThat(entry.getType()).isEqualTo(type);
        assertThat(entry.getActor()).isEqualTo("riley");
        assertThat(entry.getOccurredAt()).isEqualTo(NOW);
        assertThat(entry.getNote()).isEqualTo(note);
    }
}
