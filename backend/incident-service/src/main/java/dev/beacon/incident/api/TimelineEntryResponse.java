package dev.beacon.incident.api;

import java.time.Instant;

import dev.beacon.incident.domain.TimelineEntry;
import dev.beacon.incident.domain.TimelineEntryType;

public record TimelineEntryResponse(long id, TimelineEntryType type, String actor, Instant occurredAt, String note) {

    public static TimelineEntryResponse from(TimelineEntry entry) {
        return new TimelineEntryResponse(
                entry.getId(), entry.getType(), entry.getActor(), entry.getOccurredAt(), entry.getNote());
    }
}
