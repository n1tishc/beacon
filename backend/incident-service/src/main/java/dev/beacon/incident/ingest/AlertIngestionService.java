package dev.beacon.incident.ingest;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.beacon.alerts.AlertMessage;
import dev.beacon.incident.domain.Alert;
import dev.beacon.incident.domain.AlertRepository;
import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentRepository;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.service.IncidentEventRecorder;

/**
 * Turns one alert into an incident, or folds it into the open incident with the same fingerprint.
 *
 * <p>Each call is one transaction. Two constraints make concurrent and repeated deliveries safe, and
 * both surface as a {@code DataIntegrityViolationException} that {@link AlertListener} retries:
 * <ul>
 *   <li>the alert id is the primary key of {@code alerts}, so the same message is never counted twice;</li>
 *   <li>a partial unique index allows one open incident per fingerprint, so two alerts racing to open
 *       an incident cannot both do so.</li>
 * </ul>
 */
@Service
public class AlertIngestionService {

    public enum Outcome {
        /** The alert opened a new incident. */
        CREATED,
        /** An incident with this fingerprint was already open; the alert was counted against it. */
        DEDUPLICATED,
        /** SQS delivered this alert before; nothing changed. */
        ALREADY_PROCESSED
    }

    public record Result(Outcome outcome, UUID incidentId) {
    }

    private final IncidentRepository incidents;
    private final AlertRepository alerts;
    private final IncidentEventRecorder recorder;
    private final Clock clock;

    public AlertIngestionService(IncidentRepository incidents, AlertRepository alerts,
                                 IncidentEventRecorder recorder, Clock clock) {
        this.incidents = incidents;
        this.alerts = alerts;
        this.recorder = recorder;
        this.clock = clock;
    }

    @Transactional
    public Result ingest(AlertMessage alert) {
        Optional<Alert> seen = alerts.findById(alert.alertId());
        if (seen.isPresent()) {
            return new Result(Outcome.ALREADY_PROCESSED, seen.get().getIncidentId());
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant receivedAt = alert.receivedAt().truncatedTo(ChronoUnit.MICROS);
        Severity severity = Severity.valueOf(alert.severity().name());

        // A repeat never changes the incident's severity or title: the first alert set them, and a
        // responder may already have acted on them. The alert itself is kept with its own severity.
        Optional<UUID> open = incidents.findOpenIncidentIdByFingerprint(alert.fingerprint());
        Result result;
        if (open.isPresent()) {
            incidents.recordRepeatAlert(open.get(), receivedAt);
            result = new Result(Outcome.DEDUPLICATED, open.get());
        } else {
            Incident incident = incidents.save(Incident.triggerFromAlert(alert.title(), alert.description(),
                    severity, alert.source(), alert.fingerprint(), receivedAt, now));
            // Repeats (above) notify no one: the incident is already open and its responders know.
            recorder.record(incident, incident.triggeredBy("alert:" + alert.source()));
            result = new Result(Outcome.CREATED, incident.getId());
        }

        alerts.save(new Alert(alert.alertId(), result.incidentId(), alert.source(), alert.fingerprint(),
                alert.title(), severity, receivedAt, now));
        return result;
    }
}
