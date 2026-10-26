package dev.beacon.incident.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import dev.beacon.alerts.AlertMessage;
import dev.beacon.alerts.AlertMessageCodec;
import dev.beacon.alerts.AlertSeverity;
import dev.beacon.incident.ingest.AlertIngestionService.Outcome;
import dev.beacon.incident.ingest.AlertIngestionService.Result;

class AlertListenerTest {

    private static final Instant RECEIVED = Instant.parse("2026-01-15T10:00:00Z");
    private static final AlertMessage ALERT = new AlertMessage(UUID.randomUUID(), "prometheus", "fp", "Disk full",
            null, AlertSeverity.LOW, RECEIVED);

    private final AlertIngestionService ingestion = mock(AlertIngestionService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AlertListener listener = new AlertListener(ingestion, meters,
            Clock.fixed(RECEIVED.plusMillis(250), ZoneOffset.UTC));

    @Test
    void recordsTheLatencyFromReceiptToCommitTaggedByOutcome() {
        when(ingestion.ingest(ALERT)).thenReturn(new Result(Outcome.CREATED, UUID.randomUUID()));

        listener.onMessage(AlertMessageCodec.toJson(ALERT));

        var timer = meters.get(AlertListener.LATENCY_METRIC).tag("outcome", "created").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250);
    }

    @Test
    void retriesOnceWhenAnotherDeliveryWonTheRace() {
        when(ingestion.ingest(ALERT))
                .thenThrow(new DataIntegrityViolationException("uq_incidents_open_fingerprint"))
                .thenReturn(new Result(Outcome.DEDUPLICATED, UUID.randomUUID()));

        listener.onMessage(AlertMessageCodec.toJson(ALERT));

        verify(ingestion, times(2)).ingest(ALERT);
    }

    /** Rethrowing leaves the message on the queue; SQS redelivers it and eventually dead-letters it. */
    @Test
    void givesUpAfterTheRetrySoSqsCanRedeliver() {
        when(ingestion.ingest(any())).thenThrow(new DataIntegrityViolationException("still failing"));

        assertThatThrownBy(() -> listener.onMessage(AlertMessageCodec.toJson(ALERT)))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(ingestion, times(2)).ingest(ALERT);
    }

    @Test
    void rejectsAPoisonMessageWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> listener.onMessage("garbage"))
                .isInstanceOf(AlertMessageCodec.InvalidAlertMessageException.class);
        verifyNoInteractions(ingestion);
    }
}
