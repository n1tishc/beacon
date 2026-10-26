package dev.beacon.alerts;

import java.time.Instant;
import java.util.UUID;

/**
 * One alert on its way from the ingest Lambda to incident-service, as the body of an SQS message.
 *
 * @param alertId     assigned by the Lambda when the alert is accepted. SQS may deliver a message more
 *                    than once; incident-service uses this id to process each alert exactly once.
 * @param fingerprint identifies "the same problem": alerts with the fingerprint of an open incident are
 *                    folded into it instead of opening a new one
 * @param receivedAt  when API Gateway received the request, the start of the alert-to-incident latency
 */
public record AlertMessage(
        UUID alertId,
        String source,
        String fingerprint,
        String title,
        String description,
        AlertSeverity severity,
        Instant receivedAt) {
}
