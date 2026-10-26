package dev.beacon.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentNotFoundException;
import dev.beacon.incident.domain.IncidentRepository;
import dev.beacon.incident.domain.IncidentStatus;
import dev.beacon.incident.domain.Severity;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Mock
    private IncidentRepository repository;

    private IncidentService service;

    @BeforeEach
    void setUp() {
        service = new IncidentService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createPersistsATriggeredIncidentStampedWithTheClock() {
        when(repository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Incident created = service.create("DB connections exhausted", null, Severity.CRITICAL);

        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(created);
        assertThat(created.getStatus()).isEqualTo(IncidentStatus.TRIGGERED);
        assertThat(created.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void createTruncatesTimestampsToTheDatabasePrecision() {
        Instant withNanos = Instant.parse("2026-01-15T10:00:00.123456789Z");
        service = new IncidentService(repository, Clock.fixed(withNanos, ZoneOffset.UTC));
        when(repository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Incident created = service.create("Clock skew", null, Severity.LOW);

        assertThat(created.getCreatedAt()).isEqualTo(Instant.parse("2026-01-15T10:00:00.123456Z"));
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
}
