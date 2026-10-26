package dev.beacon.incident.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineEntryRepository extends JpaRepository<TimelineEntry, Long> {

    List<TimelineEntry> findByIncidentIdOrderByOccurredAtAscIdAsc(UUID incidentId);
}
