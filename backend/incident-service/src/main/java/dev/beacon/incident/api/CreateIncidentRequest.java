package dev.beacon.incident.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import dev.beacon.incident.domain.Severity;

public record CreateIncidentRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4000) String description,
        @NotNull Severity severity) {
}
