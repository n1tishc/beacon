package dev.beacon.incident.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /** The open incident an alert with this fingerprint belongs to, if any. Only the id, so nothing is loaded. */
    @Query("""
            select i.id from Incident i
            where i.fingerprint = :fingerprint and i.status <> dev.beacon.incident.domain.IncidentStatus.RESOLVED
            """)
    Optional<UUID> findOpenIncidentIdByFingerprint(@Param("fingerprint") String fingerprint);

    /**
     * Counts one more alert against an incident in a single atomic UPDATE. Being a bulk update, it does
     * not bump {@code version} (see {@link Incident#getAlertCount()}). SQS can deliver out of order, so
     * {@code lastAlertAt} only ever moves forward.
     */
    @Modifying
    @Query("""
            update Incident i
            set i.alertCount = i.alertCount + 1,
                i.lastAlertAt = case when i.lastAlertAt is null or i.lastAlertAt < :receivedAt
                                     then :receivedAt else i.lastAlertAt end
            where i.id = :id
            """)
    int recordRepeatAlert(@Param("id") UUID id, @Param("receivedAt") Instant receivedAt);
}
