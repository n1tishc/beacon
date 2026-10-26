package dev.beacon.alerts.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.beacon.alerts.AlertLimits;
import dev.beacon.alerts.AlertMessage;
import dev.beacon.alerts.AlertSeverity;

/**
 * Decides whether an incoming request becomes a queued alert. Knows nothing about Lambda or API
 * Gateway, so it is tested as plain Java; {@link AlertIngestHandler} adapts it to the Lambda runtime.
 */
public class AlertIngestor {

    /** What happened to one request. The handler turns each case into an HTTP response. */
    public sealed interface Outcome {

        record Accepted(UUID alertId, String fingerprint) implements Outcome {
        }

        record Rejected(int status, String title, String detail) implements Outcome {
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final byte[] apiKey;
    private final AlertPublisher publisher;

    /** Refuses to start without a key: an unauthenticated ingest endpoint would accept alerts from anyone. */
    public AlertIngestor(String apiKey, AlertPublisher publisher) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("INGEST_API_KEY must be set");
        }
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.publisher = publisher;
    }

    public Outcome ingest(String presentedKey, String body, Instant receivedAt) {
        if (!keyMatches(presentedKey)) {
            return new Outcome.Rejected(401, "Unauthorized", "Missing or invalid X-Beacon-Ingest-Key header.");
        }
        IncomingAlert alert;
        try {
            alert = body == null ? null : JSON.readValue(body, IncomingAlert.class);
        } catch (JsonProcessingException e) {
            return invalid("The body is not a valid alert JSON object.");
        }
        if (alert == null) {
            return invalid("The body is not a valid alert JSON object.");
        }
        String problem = validate(alert);
        if (problem != null) {
            return invalid(problem);
        }

        String fingerprint = isBlank(alert.fingerprint())
                ? Fingerprints.of(alert.source(), alert.title())
                : alert.fingerprint().strip();
        AlertMessage message = new AlertMessage(
                UUID.randomUUID(),
                alert.source().strip(),
                fingerprint,
                alert.title().strip(),
                isBlank(alert.description()) ? null : alert.description().strip(),
                AlertSeverity.valueOf(alert.severity()),
                receivedAt);
        publisher.publish(message);
        return new Outcome.Accepted(message.alertId(), fingerprint);
    }

    /** Constant-time comparison, so response timing does not reveal how much of a guessed key was right. */
    private boolean keyMatches(String presented) {
        return presented != null && MessageDigest.isEqual(apiKey, presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String validate(IncomingAlert alert) {
        if (isBlank(alert.source()) || alert.source().strip().length() > AlertLimits.SOURCE_MAX) {
            return "source is required (up to " + AlertLimits.SOURCE_MAX + " characters).";
        }
        if (isBlank(alert.title()) || alert.title().strip().length() > AlertLimits.TITLE_MAX) {
            return "title is required (up to " + AlertLimits.TITLE_MAX + " characters).";
        }
        if (alert.description() != null && alert.description().strip().length() > AlertLimits.DESCRIPTION_MAX) {
            return "description is limited to " + AlertLimits.DESCRIPTION_MAX + " characters.";
        }
        if (alert.fingerprint() != null && alert.fingerprint().strip().length() > AlertLimits.FINGERPRINT_MAX) {
            return "fingerprint is limited to " + AlertLimits.FINGERPRINT_MAX + " characters.";
        }
        boolean knownSeverity = Arrays.stream(AlertSeverity.values())
                .anyMatch(severity -> severity.name().equals(alert.severity()));
        if (!knownSeverity) {
            return "severity must be one of " + Arrays.toString(AlertSeverity.values()) + ".";
        }
        return null;
    }

    private static Outcome.Rejected invalid(String detail) {
        return new Outcome.Rejected(400, "Invalid alert", detail);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
