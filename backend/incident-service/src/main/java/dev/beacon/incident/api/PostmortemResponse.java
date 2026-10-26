package dev.beacon.incident.api;

import java.time.Instant;
import java.util.UUID;

import dev.beacon.incident.domain.Postmortem;
import dev.beacon.incident.domain.PostmortemContent;

/** {@code version} goes back with the next save; see {@link SavePostmortemRequest}. */
public record PostmortemResponse(
        UUID incidentId,
        long version,
        String summary,
        String impact,
        String rootCause,
        String actionItems,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {

    public static PostmortemResponse from(Postmortem postmortem) {
        PostmortemContent content = postmortem.getContent();
        return new PostmortemResponse(
                postmortem.getIncidentId(),
                postmortem.getVersion(),
                content.summary(),
                content.impact(),
                content.rootCause(),
                content.actionItems(),
                postmortem.getCreatedBy(),
                postmortem.getCreatedAt(),
                postmortem.getUpdatedBy(),
                postmortem.getUpdatedAt());
    }
}
