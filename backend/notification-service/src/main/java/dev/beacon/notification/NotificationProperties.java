package dev.beacon.notification;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param serviceToken shared secret incident-service sends on {@code /internal/**}; startup fails without one
 * @param slackWebhookUrl Slack incoming-webhook URL; blank turns the Slack channel off
 * @param publicUrl where users open Beacon, used to link each message to its incident
 */
@Validated
@ConfigurationProperties("beacon.notifications")
public record NotificationProperties(@NotBlank String serviceToken, String slackWebhookUrl, @NotBlank String publicUrl) {

    public boolean slackEnabled() {
        return slackWebhookUrl != null && !slackWebhookUrl.isBlank();
    }
}
