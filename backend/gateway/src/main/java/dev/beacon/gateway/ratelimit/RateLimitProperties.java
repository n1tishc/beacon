package dev.beacon.gateway.ratelimit;

import java.time.Duration;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Rate limits per gateway route, e.g.
 * <pre>
 * beacon.gateway.rate-limit.routes.auth-login.capacity: 10
 * beacon.gateway.rate-limit.routes.auth-login.period: 1m
 * </pre>
 *
 * @param trustedProxyHops how many proxies in front of the gateway append to X-Forwarded-For
 *                         (nginx locally, CloudFront in AWS: 1). Used to find the real client IP.
 * @param defaultLimit     applies to any route without its own entry
 * @param routes           limits keyed by route id
 */
@Validated
@ConfigurationProperties("beacon.gateway.rate-limit")
public record RateLimitProperties(
        @DefaultValue("1") @Min(1) int trustedProxyHops,
        @DefaultValue @Valid Limit defaultLimit,
        @DefaultValue Map<String, @Valid Limit> routes) {

    public Limit limitFor(String routeId) {
        return routes.getOrDefault(routeId, defaultLimit);
    }

    /** Allows bursts of up to {@code capacity} requests, refilling at {@code capacity} per {@code period}. */
    public record Limit(
            @DefaultValue("100") @Min(1) int capacity,
            @DefaultValue("10s") Duration period) {
    }
}
