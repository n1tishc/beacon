package dev.beacon.gateway.ratelimit;

/**
 * Classic token bucket: holds up to {@code capacity} tokens, refills continuously, and each
 * request takes one token. Thread-safe; one instance per (route, client) pair.
 */
final class TokenBucket {

    private final long capacity;
    private final double tokensPerNano;
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(RateLimitProperties.Limit limit, long nowNanos) {
        this.capacity = limit.capacity();
        this.tokensPerNano = (double) limit.capacity() / limit.period().toNanos();
        this.tokens = limit.capacity();
        this.lastRefillNanos = nowNanos;
    }

    synchronized boolean tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }

    synchronized long remaining() {
        return (long) tokens;
    }

    long capacity() {
        return capacity;
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
            lastRefillNanos = nowNanos;
        }
    }
}
