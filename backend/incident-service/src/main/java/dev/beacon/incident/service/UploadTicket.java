package dev.beacon.incident.service;

import dev.beacon.incident.domain.Attachment;
import dev.beacon.incident.storage.PresignedUpload;

/** An announced attachment and where to upload its bytes. */
public record UploadTicket(Attachment attachment, PresignedUpload upload) {
}
