package dev.beacon.incident.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.Exceptions;

/**
 * Calls notification-service through the same three Resilience4j layers notification-service puts
 * around Slack, configured under {@code resilience4j.*.instances.notification-service}: a time limit
 * per attempt, a circuit breaker counting attempts, and retries outside both. The fallback (the
 * {@code catch}) drops the notification and counts it in {@value #DROPPED_METRIC}.
 *
 * <p>Dropping is a choice: the incident is already committed and is what matters. A transactional
 * outbox would make delivery guaranteed; see the README's design notes.
 */
public class NotificationClient {

    public static final String INSTANCE = "notification-service";
    public static final String DROPPED_METRIC = "beacon.notifications.dropped";
    static final String SERVICE_TOKEN_HEADER = "X-Beacon-Service-Token";

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final Counter dropped;

    public NotificationClient(WebClient webClient, CircuitBreaker circuitBreaker, Retry retry,
                              TimeLimiter timeLimiter, MeterRegistry meters) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.timeLimiter = timeLimiter;
        this.dropped = meters.counter(DROPPED_METRIC);
    }

    /** Blocks until notification-service has answered or every attempt failed. Never throws. */
    public boolean send(NotificationRequest request) {
        try {
            // Each subscription sends the request again, which is what Retry relies on.
            webClient.post()
                    .uri("/internal/notifications")
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                            response -> response.releaseBody().thenReturn(new NotificationRejectedException(response.statusCode())))
                    .toBodilessEntity()
                    .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                    .transformDeferred(RetryOperator.of(retry))
                    .block();
            return true;
        } catch (RuntimeException wrapped) {
            Throwable error = Exceptions.unwrap(wrapped);
            dropped.increment();
            log.warn("Notification for incident {} (event {}) dropped: {}",
                    request.incidentId(), request.eventId(), error.toString());
            return false;
        }
    }
}
