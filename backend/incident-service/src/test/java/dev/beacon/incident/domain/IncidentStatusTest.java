package dev.beacon.incident.domain;

import static dev.beacon.incident.domain.IncidentStatus.ACKNOWLEDGED;
import static dev.beacon.incident.domain.IncidentStatus.RESOLVED;
import static dev.beacon.incident.domain.IncidentStatus.TRIGGERED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The complete transition table: every (from, to) pair, so no allowed or forbidden move is implicit. */
class IncidentStatusTest {

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
            "TRIGGERED,    TRIGGERED,    false",
            "TRIGGERED,    ACKNOWLEDGED, true",
            "TRIGGERED,    RESOLVED,     true",
            "ACKNOWLEDGED, TRIGGERED,    false",
            "ACKNOWLEDGED, ACKNOWLEDGED, false",
            "ACKNOWLEDGED, RESOLVED,     true",
            "RESOLVED,     TRIGGERED,    false",
            "RESOLVED,     ACKNOWLEDGED, false",
            "RESOLVED,     RESOLVED,     false",
    })
    void transitionTable(IncidentStatus from, IncidentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    /** Fails as soon as a status is added, as a reminder to extend the table above. */
    @ParameterizedTest
    @EnumSource(IncidentStatus.class)
    void tableAboveCoversEveryStatus(IncidentStatus status) {
        assertThat(status).isIn(TRIGGERED, ACKNOWLEDGED, RESOLVED);
    }
}
