package dev.beacon.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AlertMessageCodecTest {

    private static final AlertMessage MESSAGE = new AlertMessage(
            UUID.fromString("7d1f6c1e-2b1a-4c3e-9f00-0a1b2c3d4e5f"), "prometheus", "a1b2c3",
            "Checkout p99 above 2s", null, AlertSeverity.HIGH, Instant.parse("2026-01-15T10:00:00.123Z"));

    @Test
    void roundTrips() {
        assertThat(AlertMessageCodec.fromJson(AlertMessageCodec.toJson(MESSAGE))).isEqualTo(MESSAGE);
    }

    /** The wire format is a contract with already-queued messages; changing it must be deliberate. */
    @Test
    void writesTheDocumentedShape() {
        assertThat(AlertMessageCodec.toJson(MESSAGE)).isEqualTo("""
                {"alertId":"7d1f6c1e-2b1a-4c3e-9f00-0a1b2c3d4e5f","source":"prometheus","fingerprint":"a1b2c3",\
                "title":"Checkout p99 above 2s","description":null,"severity":"HIGH",\
                "receivedAt":"2026-01-15T10:00:00.123Z"}""");
    }

    @Test
    void ignoresFieldsAddedByANewerProducer() {
        String json = AlertMessageCodec.toJson(MESSAGE).replace("{", "{\"addedLater\":1,");

        assertThat(AlertMessageCodec.fromJson(json)).isEqualTo(MESSAGE);
    }

    @Test
    void rejectsMalformedOrIncompleteMessages() {
        assertThatThrownBy(() -> AlertMessageCodec.fromJson("not json"))
                .isInstanceOf(AlertMessageCodec.InvalidAlertMessageException.class);
        assertThatThrownBy(() -> AlertMessageCodec.fromJson("{\"title\":\"x\"}"))
                .isInstanceOf(AlertMessageCodec.InvalidAlertMessageException.class)
                .hasMessageContaining("missing required fields");
        assertThatThrownBy(() -> AlertMessageCodec.fromJson(AlertMessageCodec.toJson(MESSAGE).replace("HIGH", "APOCALYPTIC")))
                .isInstanceOf(AlertMessageCodec.InvalidAlertMessageException.class);
    }

    /** Only the description may be missing; every other field is required on its own. */
    @ParameterizedTest
    @ValueSource(strings = {"alertId", "source", "fingerprint", "title", "severity", "receivedAt"})
    void rejectsAMessageWithoutARequiredField(String field) {
        String json = AlertMessageCodec.toJson(MESSAGE).replaceFirst("\"" + field + "\":(\"[^\"]*\")", "\"" + field + "\":null");

        assertThat(json).contains("\"" + field + "\":null");
        assertThatThrownBy(() -> AlertMessageCodec.fromJson(json))
                .isInstanceOf(AlertMessageCodec.InvalidAlertMessageException.class)
                .hasMessageContaining("missing required fields");
    }

    @Test
    void rejectsAJsonNull() {
        assertThatThrownBy(() -> AlertMessageCodec.fromJson("null"))
                .isInstanceOf(AlertMessageCodec.InvalidAlertMessageException.class);
    }
}
