package dev.beacon.notification.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByEventIdOrderByChannel(long eventId);

    List<Notification> findByIncidentIdOrderByCreatedAtAscChannelAsc(UUID incidentId);
}
