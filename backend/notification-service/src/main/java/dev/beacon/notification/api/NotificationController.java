package dev.beacon.notification.api;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.beacon.notification.channel.ChannelStatus;
import dev.beacon.notification.service.NotificationService;

/** Read-only views for the UI, reached through the gateway with a user's token. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Oldest first, like the incident's timeline. */
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<NotificationResponse> forIncident(@RequestParam UUID incidentId) {
        return notificationService.forIncident(incidentId).stream().map(NotificationResponse::from).toList();
    }

    /** Each channel and, for Slack, its circuit breaker state (CLOSED, OPEN or HALF_OPEN). */
    @GetMapping("/channels")
    @PreAuthorize("hasRole('VIEWER')")
    public List<ChannelStatus> channels() {
        return notificationService.channels();
    }
}
