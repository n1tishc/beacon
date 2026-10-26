package dev.beacon.alerts;

import java.io.UncheckedIOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The one place the SQS message format is defined. The Lambda writes with it and incident-service
 * reads with it, so the two cannot drift apart.
 */
public final class AlertMessageCodec {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // A newer producer may add fields; an older consumer should still read the message.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private AlertMessageCodec() {
    }

    public static String toJson(AlertMessage message) {
        try {
            return JSON.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Throws {@link InvalidAlertMessageException} for anything that is not a complete alert message. */
    public static AlertMessage fromJson(String json) {
        AlertMessage message;
        try {
            message = JSON.readValue(json, AlertMessage.class);
        } catch (JsonProcessingException e) {
            throw new InvalidAlertMessageException("Not an alert message: " + e.getOriginalMessage(), e);
        }
        if (message == null || message.alertId() == null || message.fingerprint() == null
                || message.title() == null || message.severity() == null || message.receivedAt() == null
                || message.source() == null) {
            throw new InvalidAlertMessageException("Alert message is missing required fields", null);
        }
        return message;
    }

    public static class InvalidAlertMessageException extends RuntimeException {
        public InvalidAlertMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
