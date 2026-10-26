package dev.beacon.alerts.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.beacon.alerts.AlertMessage;
import dev.beacon.alerts.AlertSeverity;
import dev.beacon.alerts.ingest.AlertIngestor.Outcome;

class AlertIngestorTest {

    private static final String KEY = "s3cret-ingest-key";
    private static final Instant RECEIVED = Instant.parse("2026-01-15T10:00:00Z");

    private final List<AlertMessage> published = new ArrayList<>();
    private final AlertIngestor ingestor = new AlertIngestor(KEY, published::add);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"wrong-key", "s3cret-ingest-key "})
    void rejectsAMissingOrWrongKeyWithoutPublishing(String presented) {
        Outcome outcome = ingestor.ingest(presented, body("prometheus", "Disk full", "LOW"), RECEIVED);

        assertThat(outcome).isEqualTo(new Outcome.Rejected(401, "Unauthorized",
                "Missing or invalid X-Beacon-Ingest-Key header."));
        assertThat(published).isEmpty();
    }

    @Test
    void publishesAnAcceptedAlertWithADerivedFingerprint() {
        Outcome outcome = ingestor.ingest(KEY, """
                {"source": " prometheus ", "title": "Checkout p99 above 2s", "description": "  ", "severity": "HIGH"}
                """, RECEIVED);

        assertThat(published).hasSize(1);
        AlertMessage message = published.getFirst();
        assertThat(outcome).isEqualTo(new Outcome.Accepted(message.alertId(), message.fingerprint()));
        assertThat(message.source()).isEqualTo("prometheus");
        assertThat(message.title()).isEqualTo("Checkout p99 above 2s");
        assertThat(message.description()).isNull();
        assertThat(message.severity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(message.receivedAt()).isEqualTo(RECEIVED);
        assertThat(message.fingerprint()).isEqualTo(Fingerprints.of("prometheus", "Checkout p99 above 2s"));
    }

    @Test
    void usesTheSendersFingerprintWhenGiven() {
        ingestor.ingest(KEY, """
                {"source": "alertmanager", "title": "x", "severity": "LOW", "fingerprint": "group:checkout/5xx"}
                """, RECEIVED);

        assertThat(published.getFirst().fingerprint()).isEqualTo("group:checkout/5xx");
    }

    @Test
    void everyAcceptedAlertGetsItsOwnId() {
        ingestor.ingest(KEY, body("prometheus", "Disk full", "LOW"), RECEIVED);
        ingestor.ingest(KEY, body("prometheus", "Disk full", "LOW"), RECEIVED);

        assertThat(published.get(0).alertId()).isNotEqualTo(published.get(1).alertId());
        assertThat(published.get(0).fingerprint()).isEqualTo(published.get(1).fingerprint());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "missing source       | {\"title\": \"x\", \"severity\": \"LOW\"}                             | source is required",
            "missing title        | {\"source\": \"p\", \"severity\": \"LOW\"}                            | title is required",
            "unknown severity     | {\"source\": \"p\", \"title\": \"x\", \"severity\": \"low\"}          | severity must be one of",
            "missing severity     | {\"source\": \"p\", \"title\": \"x\"}                                 | severity must be one of",
            "not JSON             | severity=LOW                                                           | not a valid alert JSON object",
            "JSON null            | null                                                                   | not a valid alert JSON object",
    })
    void rejectsInvalidAlerts(String scenario, String body, String expectedDetail) {
        Outcome outcome = ingestor.ingest(KEY, body, RECEIVED);

        assertThat(outcome).isInstanceOfSatisfying(Outcome.Rejected.class, rejected -> {
            assertThat(rejected.status()).isEqualTo(400);
            assertThat(rejected.detail()).contains(expectedDetail);
        });
        assertThat(published).isEmpty();
    }

    @Test
    void rejectsFieldsLongerThanIncidentServiceCanStore() {
        String longSource = "s".repeat(49);
        String longTitle = "t".repeat(201);

        assertThat(ingestor.ingest(KEY, body(longSource, "x", "LOW"), RECEIVED)).isInstanceOf(Outcome.Rejected.class);
        assertThat(ingestor.ingest(KEY, body("p", longTitle, "LOW"), RECEIVED)).isInstanceOf(Outcome.Rejected.class);
        assertThat(published).isEmpty();
    }

    @Test
    void fingerprintsIgnoreCaseAndSpacingButNotSource() {
        assertThat(Fingerprints.of("Prometheus", "Disk  full on db-1"))
                .isEqualTo(Fingerprints.of("prometheus", " disk full on DB-1 "));
        assertThat(Fingerprints.of("prometheus", "Disk full"))
                .isNotEqualTo(Fingerprints.of("datadog", "Disk full"));
    }

    @Test
    void refusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new AlertIngestor(" ", published::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INGEST_API_KEY");
    }

    private static String body(String source, String title, String severity) {
        return "{\"source\": \"" + source + "\", \"title\": \"" + title + "\", \"severity\": \"" + severity + "\"}";
    }
}
