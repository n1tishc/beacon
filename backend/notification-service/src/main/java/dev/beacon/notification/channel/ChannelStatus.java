package dev.beacon.notification.channel;

import dev.beacon.notification.domain.Channel;

/** @param circuitState the channel's circuit breaker state, or null if it has no circuit breaker */
public record ChannelStatus(Channel channel, boolean enabled, String circuitState) {
}
