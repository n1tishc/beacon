package dev.beacon.incident.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {
}
