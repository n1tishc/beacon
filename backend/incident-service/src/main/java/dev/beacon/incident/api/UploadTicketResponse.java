package dev.beacon.incident.api;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import dev.beacon.incident.service.UploadTicket;

/**
 * Where and how to upload: {@code PUT uploadUrl} with {@code uploadHeaders} and the file as the body,
 * before {@code expiresAt}. Then {@code POST .../attachments/{id}/complete}.
 */
public record UploadTicketResponse(
        AttachmentResponse attachment,
        URI uploadUrl,
        Map<String, String> uploadHeaders,
        Instant expiresAt) {

    public static UploadTicketResponse from(UploadTicket ticket) {
        return new UploadTicketResponse(
                AttachmentResponse.from(ticket.attachment()),
                ticket.upload().url(),
                ticket.upload().headers(),
                ticket.upload().expiresAt());
    }
}
