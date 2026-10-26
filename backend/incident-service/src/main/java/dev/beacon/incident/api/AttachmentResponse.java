package dev.beacon.incident.api;

import java.time.Instant;
import java.util.UUID;

import dev.beacon.incident.domain.Attachment;
import dev.beacon.incident.domain.AttachmentStatus;

/** No S3 key or URL: downloads go through {@code .../download}, which signs a fresh, short-lived URL. */
public record AttachmentResponse(
        UUID id,
        String fileName,
        String contentType,
        long sizeBytes,
        AttachmentStatus status,
        String uploadedBy,
        Instant createdAt,
        Instant uploadedAt) {

    public static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getStatus(),
                attachment.getUploadedBy(),
                attachment.getCreatedAt(),
                attachment.getUploadedAt());
    }
}
