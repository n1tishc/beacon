package dev.beacon.incident.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.beacon.incident.service.PostmortemService;
import dev.beacon.incident.service.UploadTicket;

/** An incident's postmortem and its attachments. Same role rules as {@link IncidentController}. */
@RestController
@RequestMapping("/api/incidents/{id}/postmortem")
public class PostmortemController {

    private final PostmortemService postmortemService;

    public PostmortemController(PostmortemService postmortemService) {
        this.postmortemService = postmortemService;
    }

    /** 404 "Postmortem not found" until someone writes one. */
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public PostmortemResponse get(@PathVariable UUID id) {
        return PostmortemResponse.from(postmortemService.get(id));
    }

    /** Creates the postmortem or replaces its content; idempotent for the same version and content. */
    @PutMapping
    @PreAuthorize("hasRole('RESPONDER')")
    public PostmortemResponse save(@PathVariable UUID id, @Valid @RequestBody SavePostmortemRequest request,
                                   Authentication caller) {
        return PostmortemResponse.from(
                postmortemService.save(id, request.version(), request.toContent(), caller.getName()));
    }

    @GetMapping("/attachments")
    @PreAuthorize("hasRole('VIEWER')")
    public List<AttachmentResponse> attachments(@PathVariable UUID id) {
        return postmortemService.attachments(id).stream().map(AttachmentResponse::from).toList();
    }

    @PostMapping("/attachments")
    @PreAuthorize("hasRole('RESPONDER')")
    public ResponseEntity<UploadTicketResponse> announce(@PathVariable UUID id,
                                                         @Valid @RequestBody AnnounceAttachmentRequest request,
                                                         Authentication caller) {
        UploadTicket ticket = postmortemService.announceAttachment(
                id, request.fileName(), request.contentType(), request.sizeBytes(), caller.getName());
        URI location = URI.create("/api/incidents/" + id + "/postmortem/attachments/" + ticket.attachment().getId());
        return ResponseEntity.created(location).body(UploadTicketResponse.from(ticket));
    }

    @PostMapping("/attachments/{attachmentId}/complete")
    @PreAuthorize("hasRole('RESPONDER')")
    public AttachmentResponse complete(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        return AttachmentResponse.from(postmortemService.completeUpload(id, attachmentId));
    }

    @GetMapping("/attachments/{attachmentId}/download")
    @PreAuthorize("hasRole('VIEWER')")
    public DownloadResponse download(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        return DownloadResponse.from(postmortemService.downloadUrl(id, attachmentId));
    }
}
