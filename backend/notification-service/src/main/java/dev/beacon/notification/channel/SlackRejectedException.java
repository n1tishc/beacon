package dev.beacon.notification.channel;

import org.springframework.http.HttpStatusCode;

/**
 * Slack answered 4xx: the webhook is wrong or revoked, or the message is invalid. Retrying cannot
 * help, and it says nothing about whether Slack is up, so neither Retry nor the circuit breaker
 * counts it (see application.yml).
 */
public class SlackRejectedException extends RuntimeException {

    public SlackRejectedException(HttpStatusCode status, String body) {
        super("Slack rejected the message (" + status.value() + ")" + (body.isBlank() ? "" : ": " + body));
    }
}
