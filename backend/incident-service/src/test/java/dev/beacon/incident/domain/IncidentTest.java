package dev.beacon.incident.domain;

import static dev.beacon.incident.TestIncidents.persisted;
import static dev.beacon.incident.TestIncidents.withVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class IncidentTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(90);

    @Test
    void triggerStartsInTriggeredStateWithMatchingTimestamps() {
        Incident incident = Incident.trigger("Checkout latency spike", "p99 > 2s", Severity.HIGH, NOW);

        assertThat(incident.getId()).isNotNull();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.TRIGGERED);
        assertThat(incident.getTitle()).isEqualTo("Checkout latency spike");
        assertThat(incident.getDescription()).isEqualTo("p99 > 2s");
        assertThat(incident.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(incident.getCreatedAt()).isEqualTo(NOW);
        assertThat(incident.getUpdatedAt()).isEqualTo(NOW);
        assertThat(incident.triggeredBy("riley"))
                .isEqualTo(new IncidentEvent.Triggered(incident.getId(), "riley", NOW, Severity.HIGH));
    }

    @Test
    void acknowledgeMovesToAcknowledgedAndReturnsTheEvent() {
        Incident incident = triggered();

        IncidentEvent.Acknowledged event = incident.acknowledge("riley", LATER);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(incident.getAcknowledgedAt()).isEqualTo(LATER);
        assertThat(incident.getUpdatedAt()).isEqualTo(LATER);
        assertThat(event).isEqualTo(new IncidentEvent.Acknowledged(incident.getId(), "riley", LATER));
    }

    @Test
    void resolveWorksFromTriggeredAndFromAcknowledged() {
        Incident skippedAck = triggered();
        skippedAck.resolve("riley", "False positive", LATER);
        assertThat(skippedAck.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(skippedAck.getAcknowledgedAt()).isNull();

        Incident acknowledged = triggered();
        acknowledged.acknowledge("riley", NOW);
        IncidentEvent.Resolved event = acknowledged.resolve("avery", "Rolled back", LATER);
        assertThat(acknowledged.getResolvedAt()).isEqualTo(LATER);
        assertThat(event.resolutionNote()).isEqualTo("Rolled back");
        assertThat(event.actor()).isEqualTo("avery");
    }

    @Test
    void forbiddenTransitionsThrowAndLeaveTheIncidentUnchanged() {
        Incident incident = triggered();
        incident.resolve("riley", null, NOW);

        assertThatThrownBy(() -> incident.acknowledge("riley", LATER))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("is RESOLVED and cannot move to ACKNOWLEDGED");
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void requireVersionRejectsAnyOtherVersion() {
        Incident incident = withVersion(triggered(), 3);

        incident.requireVersion(3);
        assertThatThrownBy(() -> incident.requireVersion(2))
                .isInstanceOf(StaleIncidentException.class)
                .hasMessageContaining("you had version 2, it is now 3");
    }

    private static Incident triggered() {
        return persisted("Checkout latency spike", Severity.HIGH, NOW);
    }
}
