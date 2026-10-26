package dev.beacon.incident.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.reactive.function.client.WebClient;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Wires notifications. {@code beacon.notifications.enabled=false} switches them off entirely, for
 * tests that are about something else.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "beacon.notifications", name = "enabled", matchIfMissing = true)
@EnableAsync
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationConfig {

    @Bean
    NotificationClient notificationClient(WebClient.Builder webClients, NotificationProperties properties,
                                          CircuitBreakerRegistry circuitBreakers, RetryRegistry retries,
                                          TimeLimiterRegistry timeLimiters, MeterRegistry meters) {
        WebClient webClient = webClients
                .baseUrl(properties.url())
                .defaultHeader(NotificationClient.SERVICE_TOKEN_HEADER, properties.serviceToken())
                .build();
        return new NotificationClient(webClient,
                circuitBreakers.circuitBreaker(NotificationClient.INSTANCE),
                retries.retry(NotificationClient.INSTANCE),
                timeLimiters.timeLimiter(NotificationClient.INSTANCE),
                meters);
    }

    @Bean
    NotificationPublisher notificationPublisher(NotificationClient client) {
        return new NotificationPublisher(client);
    }
}
