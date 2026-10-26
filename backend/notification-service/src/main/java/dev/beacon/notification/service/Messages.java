package dev.beacon.notification.service;

import dev.beacon.notification.domain.IncidentEvent;

/** The text of a notification. Slack's mrkdwn: {@code <url|label>} is a link, {@code :name:} an emoji. */
final class Messages {

    private Messages() {
    }

    static String render(IncidentEvent event, String incidentUrl) {
        String headline = switch (event.type()) {
            case TRIGGERED -> ":rotating_light: [%s] %s, triggered by %s"
                    .formatted(event.severity(), event.title(), event.actor());
            case ACKNOWLEDGED -> ":eyes: %s acknowledged: %s".formatted(event.actor(), event.title());
            case RESOLVED -> ":white_check_mark: %s resolved: %s".formatted(event.actor(), event.title());
        };
        return headline + "\n<" + incidentUrl + "|Open in Beacon>";
    }
}
