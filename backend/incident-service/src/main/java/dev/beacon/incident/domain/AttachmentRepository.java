package dev.beacon.incident.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByIncidentIdAndStatusOrderByCreatedAtAsc(UUID incidentId, AttachmentStatus status);

    /** Scoped to the incident, so an attachment id from one incident is not found under another. */
    Optional<Attachment> findByIdAndIncidentId(UUID id, UUID incidentId);

    List<Attachment> findByStatusAndCreatedAtBefore(AttachmentStatus status, Instant cutoff);
}
