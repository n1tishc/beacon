package dev.beacon.incident.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentEvent;
import dev.beacon.incident.domain.TimelineEntry;
import dev.beacon.incident.domain.TimelineEntryRepository;

/**
 * The one place an {@link IncidentEvent} is recorded, whether a person or an alert caused it: as a
 * timeline entry and, for the events people are notified about, as an {@link IncidentChanged}.
 * Call it inside the transaction that changed the incident; notification listeners run only after
 * that transaction commits (see {@code NotificationPublisher}).
 */
@Component
public class IncidentEventRecorder {

    private final TimelineEntryRepository timeline;
    private final ApplicationEventPublisher events;

    public IncidentEventRecorder(TimelineEntryRepository timeline, ApplicationEventPublisher events) {
        this.timeline = timeline;
        this.events = events;
    }

    public TimelineEntry record(Incident incident, IncidentEvent event) {
        TimelineEntry entry = timeline.save(TimelineEntry.of(event));
        if (notifiesPeople(event)) {
            events.publishEvent(IncidentChanged.of(incident, entry));
        }
        return entry;
    }

    /** Exhaustive over the sealed interface: a new event type must decide here whether it notifies. */
    private static boolean notifiesPeople(IncidentEvent event) {
        return switch (event) {
            case IncidentEvent.Triggered triggered -> true;
            case IncidentEvent.Acknowledged acknowledged -> true;
            case IncidentEvent.Resolved resolved -> true;
            case IncidentEvent.NoteAdded noteAdded -> false;
        };
    }
}
