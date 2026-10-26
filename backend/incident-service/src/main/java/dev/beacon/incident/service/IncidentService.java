package dev.beacon.incident.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentEvent;
import dev.beacon.incident.domain.IncidentNotFoundException;
import dev.beacon.incident.domain.IncidentRepository;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.domain.TimelineEntry;
import dev.beacon.incident.domain.TimelineEntryRepository;

/**
 * Use cases for incidents. Each command runs in one transaction: the state change and its timeline
 * entry commit together or not at all, and notifications go out only after the commit.
 */
@Service
@Transactional(readOnly = true)
public class IncidentService {

    static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final IncidentRepository incidents;
    private final TimelineEntryRepository timeline;
    private final IncidentEventRecorder recorder;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, TimelineEntryRepository timeline,
                           IncidentEventRecorder recorder, Clock clock) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.recorder = recorder;
        this.clock = clock;
    }

    @Transactional
    public Incident create(String title, String description, Severity severity, String actor) {
        Incident incident = incidents.save(Incident.trigger(title, description, severity, now()));
        recorder.record(incident, incident.triggeredBy(actor));
        return incident;
    }

    public Page<Incident> list(int page, int size) {
        return incidents.findAll(PageRequest.of(page, size, NEWEST_FIRST));
    }

    public Incident get(UUID id) {
        return incidents.findById(id).orElseThrow(() -> new IncidentNotFoundException(id));
    }

    /**
     * Throws {@code StaleIncidentException} if {@code expectedVersion} is out of date and
     * {@code IllegalTransitionException} if the incident cannot be acknowledged. If another
     * transaction changes the incident between the version check and the commit, Hibernate's
     * {@code @Version} check fails the commit with {@code ObjectOptimisticLockingFailureException}.
     */
    @Transactional
    public Incident acknowledge(UUID id, long expectedVersion, String actor) {
        Incident incident = get(id);
        incident.requireVersion(expectedVersion);
        recorder.record(incident, incident.acknowledge(actor, now()));
        return incident;
    }

    /** Same guarantees as {@link #acknowledge}. */
    @Transactional
    public Incident resolve(UUID id, long expectedVersion, String actor, String resolutionNote) {
        Incident incident = get(id);
        incident.requireVersion(expectedVersion);
        recorder.record(incident, incident.resolve(actor, resolutionNote, now()));
        return incident;
    }

    /** Notes only append to the timeline and never change the incident, so they need no version. */
    @Transactional
    public TimelineEntry addNote(UUID id, String actor, String text) {
        Incident incident = get(id);
        return recorder.record(incident, new IncidentEvent.NoteAdded(incident.getId(), actor, now(), text));
    }

    /** Oldest first, the order a timeline is read in. */
    public List<TimelineEntry> timeline(UUID id) {
        Incident incident = get(id);
        return timeline.findByIncidentIdOrderByOccurredAtAscIdAsc(incident.getId());
    }

    /**
     * Postgres stores timestamps with microsecond precision. Truncating up front means the
     * response to a command matches what a later read returns.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
