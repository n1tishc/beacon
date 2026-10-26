package dev.beacon.incident.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import dev.beacon.incident.domain.PostmortemContent;

/**
 * Writes or revises a postmortem. {@code version} is null for the first draft and otherwise the
 * version last loaded, as with incident commands. Blank optional sections are stored as empty (null).
 */
public record SavePostmortemRequest(
        @PositiveOrZero Long version,
        @NotBlank @Size(max = 4000) String summary,
        @Size(max = 4000) String impact,
        @Size(max = 4000) String rootCause,
        @Size(max = 4000) String actionItems) {

    PostmortemContent toContent() {
        return new PostmortemContent(summary.strip(), blankToNull(impact), blankToNull(rootCause),
                blankToNull(actionItems));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
