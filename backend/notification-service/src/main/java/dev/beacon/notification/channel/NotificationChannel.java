package dev.beacon.notification.channel;

import dev.beacon.notification.domain.Channel;

/** Somewhere a notification can be delivered. Sealed: the set of channels is closed and known. */
public sealed interface NotificationChannel permits SlackChannel, MockChannel {

    Channel channel();

    /** A disabled channel gets no notifications at all (no rows, no attempts). */
    boolean enabled();

    /** Never throws: every failure is reported as a {@link DeliveryResult}. */
    DeliveryResult deliver(String message);

    ChannelStatus status();
}
