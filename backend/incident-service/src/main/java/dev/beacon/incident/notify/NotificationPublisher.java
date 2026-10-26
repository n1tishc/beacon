package dev.beacon.incident.notify;

import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import dev.beacon.incident.service.IncidentChanged;

/**
 * Hands each {@link IncidentChanged} to notification-service, and keeps that call away from the
 * code that changed the incident:
 * <ul>
 *   <li><b>after commit</b>: a rolled-back change (an alert that lost a race and is retried, say)
 *       never notifies anyone;</li>
 *   <li><b>{@code @Async}</b>, on a virtual thread: the API response and the SQS listener never wait
 *       for notification-service, and nothing it does can fail them.</li>
 * </ul>
 */
public class NotificationPublisher {

    private final NotificationClient client;

    public NotificationPublisher(NotificationClient client) {
        this.client = client;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentChanged(IncidentChanged change) {
        client.send(NotificationRequest.from(change));
    }
}
