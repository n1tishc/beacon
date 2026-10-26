package dev.beacon.incident.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** The file the browser is about to upload. Which types and sizes are allowed is AttachmentPolicy's call. */
public record AnnounceAttachmentRequest(
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank @Size(max = 100) String contentType,
        @Positive long sizeBytes) {
}
