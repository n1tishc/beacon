package dev.beacon.incident.notify;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param url notification-service's base URL
 * @param serviceToken shared secret notification-service expects on {@code /internal/**}
 */
@Validated
@ConfigurationProperties("beacon.notifications")
public record NotificationProperties(@NotBlank String url, @NotBlank String serviceToken) {
}
