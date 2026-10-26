package dev.beacon.notification.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.beacon.notification.service.NotificationService;

/**
 * Called by incident-service only, with the shared service token (see SecurityConfig). The gateway
 * has no route to {@code /internal/**}, so browsers cannot reach it even with a valid user token.
 */
@RestController
public class InternalNotificationController {

    private final NotificationService notificationService;

    public InternalNotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 200 with the outcome per channel, also when delivery failed: the request itself was handled. */
    @PostMapping("/internal/notifications")
    public List<NotificationResponse> notify(@Valid @RequestBody IncidentEventRequest request) {
        return notificationService.notify(request.toEvent()).stream().map(NotificationResponse::from).toList();
    }
}
