package dev.beacon.incident.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class IncidentTest {

    @Test
    void triggerStartsInTriggeredStateWithMatchingTimestamps() {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");

        Incident incident = Incident.trigger("Checkout latency spike", "p99 > 2s", Severity.HIGH, now);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.TRIGGERED);
        assertThat(incident.getTitle()).isEqualTo("Checkout latency spike");
        assertThat(incident.getDescription()).isEqualTo("p99 > 2s");
        assertThat(incident.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(incident.getCreatedAt()).isEqualTo(now);
        assertThat(incident.getUpdatedAt()).isEqualTo(now);
    }
}
