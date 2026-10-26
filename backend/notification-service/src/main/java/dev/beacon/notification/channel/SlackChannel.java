package dev.beacon.notification.channel;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import dev.beacon.notification.NotificationProperties;
import dev.beacon.notification.domain.Channel;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * Posts to a Slack incoming webhook. Each delivery goes through three Resilience4j layers,
 * configured under {@code resilience4j.*.instances.slack} in application.yml. From the inside out:
 * <ol>
 *   <li><b>time limiter</b>: one attempt may take this long; a timeout is a failure like any other;</li>
 *   <li><b>circuit breaker</b>: counts every attempt. Once enough of them fail it opens and calls
 *       fail at once, without touching Slack, until a trial call succeeds again;</li>
 *   <li><b>retry</b>: retries 5xx, connection errors and timeouts with backoff, but not 4xx and not
 *       an open circuit.</li>
 * </ol>
 * The fallback is the {@code catch} around all three, so Retry sees every failure before it is
 * turned into a result. Errors never include the webhook URL: it is a secret.
 */
@Component
public final class SlackChannel implements NotificationChannel {

    static final String INSTANCE = "slack";

    private static final Logger log = LoggerFactory.getLogger(SlackChannel.class);

    private final WebClient webClient;
    private final String webhookUrl;
    private final boolean enabled;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public SlackChannel(WebClient.Builder webClientBuilder, NotificationProperties properties,
                        CircuitBreakerRegistry circuitBreakers, RetryRegistry retries, TimeLimiterRegistry timeLimiters) {
        this.webClient = webClientBuilder.build();
        this.webhookUrl = properties.slackWebhookUrl();
        this.enabled = properties.slackEnabled();
        this.circuitBreaker = circuitBreakers.circuitBreaker(INSTANCE);
        this.retry = retries.retry(INSTANCE);
        this.timeLimiter = timeLimiters.timeLimiter(INSTANCE);
    }

    @Override
    public Channel channel() {
        return Channel.SLACK;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public DeliveryResult deliver(String message) {
        AtomicInteger attempts = new AtomicInteger();
        try {
            // defer: Retry re-subscribes, and each subscription is one more real call to Slack.
            Mono.defer(() -> {
                        attempts.incrementAndGet();
                        return post(message);
                    })
                    .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                    .transformDeferred(RetryOperator.of(retry))
                    .block();
            return new DeliveryResult.Sent(attempts.get());
        } catch (RuntimeException wrapped) {
            Throwable error = Exceptions.unwrap(wrapped);
            if (error instanceof CallNotPermittedException && attempts.get() == 0) {
                return new DeliveryResult.Skipped("Slack circuit breaker is open; Slack was not called");
            }
            String reason = describe(error);
            log.warn("Slack delivery failed after {} attempt(s): {}", attempts.get(), reason);
            return new DeliveryResult.Failed(attempts.get(), reason);
        }
    }

    @Override
    public ChannelStatus status() {
        return new ChannelStatus(Channel.SLACK, enabled, enabled ? circuitBreaker.getState().name() : null);
    }

    private Mono<Void> post(String message) {
        return webClient.post()
                .uri(webhookUrl)
                .bodyValue(Map.of("text", message))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new SlackRejectedException(response.statusCode(), body)))
                .toBodilessEntity()
                .then();
    }

    private String describe(Throwable error) {
        return switch (error) {
            case TimeoutException timeout -> "Slack did not answer within "
                    + seconds(timeLimiter.getTimeLimiterConfig().getTimeoutDuration());
            case SlackRejectedException rejected -> rejected.getMessage();
            // Not getMessage(): it contains the request URL.
            case WebClientResponseException response -> "Slack answered " + response.getStatusCode().value();
            case WebClientRequestException request ->
                    "Could not reach Slack: " + NestedExceptionUtils.getMostSpecificCause(request).getMessage();
            case CallNotPermittedException open -> "Slack circuit breaker opened during retries";
            default -> "Unexpected error: " + error.getClass().getSimpleName();
        };
    }

    private static String seconds(Duration duration) {
        return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + "s" : duration.toMillis() + "ms";
    }
}
