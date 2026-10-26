package dev.beacon.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import dev.beacon.notification.NotificationProperties;
import dev.beacon.notification.channel.DeliveryResult;
import dev.beacon.notification.channel.MockChannel;
import dev.beacon.notification.channel.SlackChannel;
import dev.beacon.notification.domain.Channel;
import dev.beacon.notification.domain.EventType;
import dev.beacon.notification.domain.IncidentEvent;
import dev.beacon.notification.domain.Notification;
import dev.beacon.notification.domain.NotificationRepository;
import dev.beacon.notification.domain.NotificationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final IncidentEvent EVENT = new IncidentEvent(7, UUID.randomUUID(), EventType.RESOLVED,
            "Checkout is down", "HIGH", "responder", NOW);

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final SlackChannel slack = mock(SlackChannel.class);
    private final MockChannel mockSink = new MockChannel();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private NotificationService service;

    @BeforeEach
    void setUp() {
        when(slack.channel()).thenReturn(Channel.SLACK);
        when(slack.enabled()).thenReturn(true);
        service = new NotificationService(repository, List.of(slack, mockSink),
                new NotificationProperties("token", "https://hooks.example", "https://beacon.example"),
                meters, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordsTheOutcomeOfEachChannel() {
        when(slack.deliver(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new DeliveryResult.Failed(3, "Slack answered 503"));

        List<Notification> result = service.notify(EVENT);

        assertThat(result).extracting(Notification::getChannel, Notification::getStatus, Notification::getAttempts)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Channel.MOCK, NotificationStatus.SENT, 1),
                        org.assertj.core.groups.Tuple.tuple(Channel.SLACK, NotificationStatus.FAILED, 3));
        assertThat(result.get(1).getDetail()).isEqualTo("Slack answered 503");
        assertThat(result.get(0).getMessage())
                .isEqualTo(":white_check_mark: responder resolved: Checkout is down\n"
                        + "<https://beacon.example/incidents/" + EVENT.incidentId() + "|Open in Beacon>");
        assertThat(meters.counter(NotificationService.DELIVERIES_METRIC, "channel", "SLACK", "status", "FAILED").count())
                .isEqualTo(1);
    }

    @Test
    void aDisabledChannelGetsNoNotification() {
        when(slack.enabled()).thenReturn(false);

        List<Notification> result = service.notify(EVENT);

        assertThat(result).extracting(Notification::getChannel).containsExactly(Channel.MOCK);
        verify(slack, never()).deliver(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void anEventAlreadyRecordedIsNotDeliveredAgain() {
        Notification earlier = Notification.pending(EVENT, Channel.SLACK, "text", NOW);
        when(repository.findByEventIdOrderByChannel(EVENT.eventId())).thenReturn(List.of(earlier));

        assertThat(service.notify(EVENT)).containsExactly(earlier);
        verify(slack, never()).deliver(org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void whenAConcurrentRequestRecordedTheEventFirstItsRecordIsReturned() {
        Notification winner = Notification.pending(EVENT, Channel.SLACK, "text", NOW);
        when(repository.findByEventIdOrderByChannel(EVENT.eventId())).thenReturn(List.of(), List.of(winner));
        when(repository.saveAllAndFlush(anyList())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThat(service.notify(EVENT)).containsExactly(winner);
        verify(slack, never()).deliver(org.mockito.ArgumentMatchers.anyString());
    }
}
