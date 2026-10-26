package dev.beacon.incident.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** See {@link AcknowledgeRequest} for {@code version}. {@code note} is an optional resolution summary. */
public record ResolveRequest(
        @NotNull @PositiveOrZero Long version,
        @Size(max = 4000) String note) {
}
