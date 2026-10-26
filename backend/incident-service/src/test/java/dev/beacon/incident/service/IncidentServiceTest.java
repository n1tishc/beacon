package dev.beacon.incident.service;

import static dev.beacon.incident.TestIncidents.persisted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import dev.beacon.incident.domain.IllegalTransitionException;
import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentNotFoundException;
import dev.beacon.incident.domain.IncidentRepository;
import dev.beacon.incident.domain.IncidentStatus;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.domain.StaleIncidentException;
import dev.beacon.incident.domain.TimelineEntry;
import dev.beacon.incident.domain.TimelineEntryRepository;
import dev.beacon.incident.domain.TimelineEntryType;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Mock
    private IncidentRepository repository;

    @Mock
    private TimelineEntryRepository timeline;

    @Mock
    private ApplicationEventPublisher events;

    private IncidentService service;

    @BeforeEach
    void setUp() {
        service = serviceAt(NOW);
        // What the database does on insert: the entry gets its id.
        lenient().when(timeline.save(any(TimelineEntry.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    }

    @Test
    void createPersistsATriggeredIncidentAndRecordsWhoTriggeredIt() {
        when(repository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Incident created = service.create("DB connections exhausted", null, Severity.CRITICAL, "riley");

        assertThat(created.getStatus()).isEqualTo(IncidentStatus.TRIGGERED);
        assertThat(created.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
        TimelineEntry entry = recordedEntry();
        assertThat(entry.getIncidentId()).isEqualTo(created.getId());
        assertThat(entry.getType()).isEqualTo(TimelineEntryType.TRIGGERED);
        assertThat(entry.getActor()).isEqualTo("riley");
        assertThat(publishedChange()).isEqualTo(new IncidentChanged(entry.getId(), created.getId(),
                TimelineEntryType.TRIGGERED, "DB connections exhausted", Severity.CRITICAL, "riley", NOW));
    }

    @Test
    void createTruncatesTimestampsToTheDatabasePrecision() {
        Instant withNanos = Instant.parse("2026-01-15T10:00:00.123456789Z");
        service = serviceAt(withNanos);
        when(repository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Incident created = service.create("Clock skew", null, Severity.LOW, "riley");

        assertThat(created.getCreatedAt()).isEqualTo(Instant.parse("2026-01-15T10:00:00.123456Z"));
        assertThat(recordedEntry().getOccurredAt()).isEqualTo(created.getCreatedAt());
    }

    @Test
    void listRequestsTheGivenPageSortedNewestFirst() {
        Incident incident = Incident.trigger("Disk full", null, Severity.LOW, NOW);
        when(repository.findAll(any(Pageable.class))).thenAnswer(invocation ->
                new PageImpl<>(List.of(incident), invocation.getArgument(0), 1));

        var page = service.list(2, 10);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(pageable.capture());
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(2, 10, IncidentService.NEWEST_FIRST));
        assertThat(page.getContent()).containsExactly(incident);
    }

    @Test
    void getThrowsNotFoundForUnknownId() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(IncidentNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void acknowledgeChangesStatusAndRecordsTheEvent() {
        Incident incident = stored();

        service.acknowledge(incident.getId(), 0, "riley");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(recordedEntry().getType()).isEqualTo(TimelineEntryType.ACKNOWLEDGED);
        assertThat(publishedChange().type()).isEqualTo(TimelineEntryType.ACKNOWLEDGED);
    }

    @Test
    void resolveKeepsTheResolutionNoteOnTheTimeline() {
        Incident incident = stored();

        service.resolve(incident.getId(), 0, "riley", "Rolled back the deploy");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(recordedEntry().getNote()).isEqualTo("Rolled back the deploy");
        assertThat(publishedChange().type()).isEqualTo(TimelineEntryType.RESOLVED);
    }

    @Test
    void aStaleVersionIsRejectedBeforeTheStateMachineIsConsulted() {
        Incident incident = stored();
        incident.resolve("avery", null, NOW);

        // Version 5 is stale *and* RESOLVED -> ACKNOWLEDGED is illegal; the stale check wins.
        assertThatThrownBy(() -> service.acknowledge(incident.getId(), 5, "riley"))
                .isInstanceOf(StaleIncidentException.class);
        verify(timeline, never()).save(any());
    }

    @Test
    void anIllegalTransitionRecordsNothing() {
        Incident incident = stored();
        incident.resolve("avery", null, NOW);

        assertThatThrownBy(() -> service.acknowledge(incident.getId(), 0, "riley"))
                .isInstanceOf(IllegalTransitionException.class);
        verify(timeline, never()).save(any());
        verifyNoInteractions(events);
    }

    /** Notes are for the people already working on the incident; they notify no one. */
    @Test
    void addNoteAppendsToTheTimelineWithoutTouchingTheIncidentOrNotifying() {
        Incident incident = stored();

        TimelineEntry entry = service.addNote(incident.getId(), "riley", "Paged the DB team");

        assertThat(entry.getType()).isEqualTo(TimelineEntryType.NOTE);
        assertThat(entry.getNote()).isEqualTo("Paged the DB team");
        assertThat(incident.getUpdatedAt()).isEqualTo(incident.getCreatedAt());
        verify(repository, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void timelineOfAnUnknownIncidentIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.timeline(id)).isInstanceOf(IncidentNotFoundException.class);
    }

    private IncidentService serviceAt(Instant now) {
        return new IncidentService(repository, timeline, new IncidentEventRecorder(timeline, events),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private IncidentChanged publishedChange() {
        ArgumentCaptor<IncidentChanged> change = ArgumentCaptor.forClass(IncidentChanged.class);
        verify(events).publishEvent(change.capture());
        return change.getValue();
    }

    private static TimelineEntry withId(TimelineEntry entry) {
        ReflectionTestUtils.setField(entry, "id", 41L);
        return entry;
    }

    private Incident stored() {
        Incident incident = persisted("Checkout 502s", Severity.HIGH, NOW.minusSeconds(60));
        when(repository.findById(incident.getId())).thenReturn(Optional.of(incident));
        return incident;
    }

    private TimelineEntry recordedEntry() {
        ArgumentCaptor<TimelineEntry> entry = ArgumentCaptor.forClass(TimelineEntry.class);
        verify(timeline).save(entry.capture());
        return entry.getValue();
    }
}
