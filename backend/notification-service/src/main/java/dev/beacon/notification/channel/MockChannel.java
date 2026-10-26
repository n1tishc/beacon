package dev.beacon.notification.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.beacon.notification.domain.Channel;

/**
 * The mock sink: logs the message and always succeeds. Every environment has it, so a notification
 * is recorded even where no Slack workspace is connected, and the UI always has something to show.
 */
@Component
public final class MockChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(MockChannel.class);

    @Override
    public Channel channel() {
        return Channel.MOCK;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public DeliveryResult deliver(String message) {
        log.info("[mock sink] {}", message);
        return new DeliveryResult.Sent(1);
    }

    @Override
    public ChannelStatus status() {
        return new ChannelStatus(Channel.MOCK, true, null);
    }
}
