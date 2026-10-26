package dev.beacon.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import dev.beacon.notification.NotificationProperties;
import dev.beacon.notification.channel.ChannelStatus;
import dev.beacon.notification.channel.DeliveryResult;
import dev.beacon.notification.channel.NotificationChannel;
import dev.beacon.notification.domain.IncidentEvent;
import dev.beacon.notification.domain.Notification;
import dev.beacon.notification.domain.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Records one notification per enabled channel, then delivers each and records how it went.
 *
 * <p>Deliberately not one transaction: the PENDING rows commit before any channel is called, so no
 * database connection is held while Slack is slow, and a delivery that never finishes stays visible.
 */
@Service
public class NotificationService {

    static final String DELIVERIES_METRIC = "beacon.notifications.deliveries";

    private final NotificationRepository notifications;
    private final List<NotificationChannel> channels;
    private final NotificationProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, List<NotificationChannel> channels,
                               NotificationProperties properties, MeterRegistry meters, Clock clock) {
        this.notifications = notifications;
        this.channels = channels.stream().sorted(Comparator.comparing(NotificationChannel::channel)).toList();
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * Idempotent per event id: a repeated request returns what the first one recorded and notifies
     * no one again. Returns once every channel has an outcome.
     */
    public List<Notification> notify(IncidentEvent event) {
        List<Notification> existing = notifications.findByEventIdOrderByChannel(event.eventId());
        if (!existing.isEmpty()) {
            return existing;
        }

        String message = Messages.render(event, properties.publicUrl() + "/incidents/" + event.incidentId());
        List<NotificationChannel> enabled = channels.stream().filter(NotificationChannel::enabled).toList();
        Instant now = now();
        List<Notification> pending = enabled.stream()
                .map(channel -> Notification.pending(event, channel.channel(), message, now))
                .toList();
        try {
            notifications.saveAllAndFlush(pending);
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            // The same event arrived twice at once and the other request recorded it first.
            return notifications.findByEventIdOrderByChannel(event.eventId());
        }

        for (int i = 0; i < pending.size(); i++) {
            Notification notification = pending.get(i);
            record(notification, enabled.get(i).deliver(message));
            notifications.save(notification);
        }
        return pending;
    }

    public List<Notification> forIncident(UUID incidentId) {
        return notifications.findByIncidentIdOrderByCreatedAtAscChannelAsc(incidentId);
    }

    public List<ChannelStatus> channels() {
        return channels.stream().map(NotificationChannel::status).toList();
    }

    private void record(Notification notification, DeliveryResult result) {
        switch (result) {
            case DeliveryResult.Sent(int attempts) -> notification.sent(attempts, now());
            case DeliveryResult.Failed(int attempts, String reason) -> notification.failed(attempts, reason, now());
            case DeliveryResult.Skipped(String reason) -> notification.skipped(reason, now());
        }
        meters.counter(DELIVERIES_METRIC,
                "channel", notification.getChannel().name(),
                "status", notification.getStatus().name()).increment();
    }

    /** Postgres keeps microseconds; truncating means a response matches a later read. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
