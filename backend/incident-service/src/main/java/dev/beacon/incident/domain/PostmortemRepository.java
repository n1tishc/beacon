package dev.beacon.incident.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PostmortemRepository extends JpaRepository<Postmortem, UUID> {
}
