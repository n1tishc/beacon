package dev.beacon.incident.notify;

import org.springframework.http.HttpStatusCode;

/**
 * notification-service answered 4xx: a bad request or a wrong service token. Sending the same
 * request again cannot help, so it is neither retried nor counted against the circuit breaker.
 */
public class NotificationRejectedException extends RuntimeException {

    public NotificationRejectedException(HttpStatusCode status) {
        super("notification-service rejected the request (" + status.value() + ")");
    }
}
