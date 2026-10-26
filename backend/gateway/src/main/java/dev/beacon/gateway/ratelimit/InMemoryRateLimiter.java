package dev.beacon.gateway.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.function.LongSupplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;

import reactor.core.publisher.Mono;

/**
 * A {@link RateLimiter} for Spring Cloud Gateway's {@code RequestRateLimiter} filter that keeps
 * token buckets in local memory.
 * <p>
 * Tradeoff: limits are per gateway instance. Beacon runs a single gateway, so this avoids running
 * Redis just for rate limiting. With several instances, swap in the stock {@code RedisRateLimiter},
 * which the route configuration accepts unchanged.
 */
public class InMemoryRateLimiter implements RateLimiter<RateLimitProperties.Limit> {

    private final RateLimitProperties properties;
    private final LongSupplier nanoClock;
    // Idle clients' buckets expire, so memory stays bounded however many IPs we see.
    private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    public InMemoryRateLimiter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    InMemoryRateLimiter(RateLimitProperties properties, LongSupplier nanoClock) {
        this.properties = properties;
        this.nanoClock = nanoClock;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String clientKey) {
        RateLimitProperties.Limit limit = properties.limitFor(routeId);
        TokenBucket bucket = buckets.get(routeId + "|" + clientKey, key -> new TokenBucket(limit, nanoClock.getAsLong()));
        boolean allowed = bucket.tryConsume(nanoClock.getAsLong());
        // Same header names as the Redis limiter, so clients see one convention.
        return Mono.just(new Response(allowed, Map.of(
                "X-RateLimit-Remaining", Long.toString(bucket.remaining()),
                "X-RateLimit-Burst-Capacity", Long.toString(bucket.capacity()))));
    }

    @Override
    public Map<String, RateLimitProperties.Limit> getConfig() {
        return properties.routes();
    }

    @Override
    public Class<RateLimitProperties.Limit> getConfigClass() {
        return RateLimitProperties.Limit.class;
    }

    @Override
    public RateLimitProperties.Limit newConfig() {
        return properties.defaultLimit();
    }
}
