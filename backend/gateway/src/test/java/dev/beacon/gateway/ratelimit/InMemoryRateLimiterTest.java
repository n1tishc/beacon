package dev.beacon.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final RateLimitProperties properties = new RateLimitProperties(
            1,
            new RateLimitProperties.Limit(2, Duration.ofSeconds(10)),
            Map.of("login", new RateLimitProperties.Limit(1, Duration.ofMinutes(1))));
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(properties, nanos::get);

    @Test
    void allowsABurstUpToCapacityThenRejects() {
        assertThat(allowed("api", "alice")).isTrue();
        assertThat(allowed("api", "alice")).isTrue();
        assertThat(allowed("api", "alice")).isFalse();
    }

    @Test
    void refillsContinuouslyOverThePeriod() {
        allowed("api", "alice");
        allowed("api", "alice");

        nanos.addAndGet(Duration.ofSeconds(5).toNanos()); // half the period refills one token

        assertThat(allowed("api", "alice")).isTrue();
        assertThat(allowed("api", "alice")).isFalse();
    }

    @Test
    void keepsSeparateBucketsPerClientAndPerRoute() {
        allowed("login", "alice");

        assertThat(allowed("login", "alice")).isFalse();
        assertThat(allowed("login", "bob")).isTrue();
        assertThat(allowed("api", "alice")).isTrue();
    }

    @Test
    void reportsRemainingTokensInHeaders() {
        var response = limiter.isAllowed("api", "alice").block();

        assertThat(response.getHeaders())
                .containsEntry("X-RateLimit-Remaining", "1")
                .containsEntry("X-RateLimit-Burst-Capacity", "2");
    }

    private boolean allowed(String routeId, String client) {
        return limiter.isAllowed(routeId, client).block().isAllowed();
    }
}
