package dev.beacon.incident.ingest;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import dev.beacon.alerts.AlertMessage;
import dev.beacon.alerts.AlertMessageCodec;

/**
 * Consumes the alert queue the ingest Lambda writes to.
 *
 * <p>A message is deleted from SQS only when this method returns normally. If it throws, SQS makes
 * the message visible again after the queue's visibility timeout, and after three failed receives
 * moves it to the dead-letter queue. That covers both poison messages and outages of the database.
 */
@Component
public class AlertListener {

    static final String LATENCY_METRIC = "beacon.alerts.latency";

    private static final Logger log = LoggerFactory.getLogger(AlertListener.class);

    private final AlertIngestionService ingestion;
    private final MeterRegistry meters;
    private final Clock clock;

    public AlertListener(AlertIngestionService ingestion, MeterRegistry meters, Clock clock) {
        this.ingestion = ingestion;
        this.meters = meters;
        this.clock = clock;
    }

    @SqsListener("${beacon.alerts.queue}")
    public void onMessage(String body) {
        AlertMessage alert = AlertMessageCodec.fromJson(body);
        AlertIngestionService.Result result = ingestWithOneRetry(alert);

        // The transaction has committed by now, so this is the full alert-to-incident latency:
        // from API Gateway receiving the request to the incident being visible in the database.
        Duration latency = Duration.between(alert.receivedAt(), clock.instant());
        latencyTimer(result.outcome()).record(latency);
        log.info("Alert {} fingerprint={} -> {} incident {} ({} ms after receipt)", alert.alertId(),
                alert.fingerprint(), result.outcome(), result.incidentId(), latency.toMillis());
    }

    /**
     * A constraint violation means another delivery won a race (same alert, or same fingerprint) and
     * committed first. Trying again in a fresh transaction sees its result and folds into it. This runs
     * outside the transactional service method on purpose: a retry inside it would reuse the failed
     * transaction.
     */
    private AlertIngestionService.Result ingestWithOneRetry(AlertMessage alert) {
        try {
            return ingestion.ingest(alert);
        } catch (DataIntegrityViolationException raceLost) {
            log.debug("Alert {} lost a race, retrying once", alert.alertId());
            return ingestion.ingest(alert);
        }
    }

    /** Percentiles are computed in-process so they can be read straight from /actuator/metrics. */
    private Timer latencyTimer(AlertIngestionService.Outcome outcome) {
        return Timer.builder(LATENCY_METRIC)
                .description("Time from API Gateway receiving an alert to its incident being committed")
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meters);
    }
}
