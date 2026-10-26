package dev.beacon.incident.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.beacon.incident.domain.Attachment;
import dev.beacon.incident.domain.AttachmentNotFoundException;
import dev.beacon.incident.domain.AttachmentPolicy;
import dev.beacon.incident.domain.AttachmentRejectedException;
import dev.beacon.incident.domain.AttachmentRepository;
import dev.beacon.incident.domain.AttachmentStatus;
import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentNotFoundException;
import dev.beacon.incident.domain.IncidentRepository;
import dev.beacon.incident.domain.Postmortem;
import dev.beacon.incident.domain.PostmortemContent;
import dev.beacon.incident.domain.PostmortemNotFoundException;
import dev.beacon.incident.domain.PostmortemRepository;
import dev.beacon.incident.domain.StalePostmortemException;
import dev.beacon.incident.domain.UploadNotFinishedException;
import dev.beacon.incident.storage.AttachmentStorage;
import dev.beacon.incident.storage.PresignedDownload;
import dev.beacon.incident.storage.StoredObject;

/**
 * Use cases for an incident's postmortem and its attachments.
 *
 * <p>An attachment is uploaded in three steps, so that file bytes never pass through this service:
 * {@link #announceAttachment} records it and signs an upload URL, the browser PUTs the file to S3,
 * and {@link #completeUpload} checks what arrived before the attachment is listed.
 */
@Service
@Transactional(readOnly = true)
public class PostmortemService {

    private final IncidentRepository incidents;
    private final PostmortemRepository postmortems;
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final Clock clock;

    public PostmortemService(IncidentRepository incidents, PostmortemRepository postmortems,
                             AttachmentRepository attachments, AttachmentStorage storage, Clock clock) {
        this.incidents = incidents;
        this.postmortems = postmortems;
        this.attachments = attachments;
        this.storage = storage;
        this.clock = clock;
    }

    public Postmortem get(UUID incidentId) {
        Incident incident = incident(incidentId);
        return postmortems.findById(incident.getId()).orElseThrow(() -> new PostmortemNotFoundException(incidentId));
    }

    /**
     * Writes the postmortem, or revises it. {@code expectedVersion} is null when the caller believes
     * there is none yet; if someone else wrote one in the meantime, that is as stale as an outdated version.
     * The flush happens here rather than at commit, so a lost race surfaces as a {@link StalePostmortemException}.
     */
    @Transactional
    public Postmortem save(UUID incidentId, Long expectedVersion, PostmortemContent content, String actor) {
        Incident incident = incident(incidentId);
        Optional<Postmortem> existing = postmortems.findById(incident.getId());
        Postmortem postmortem;
        if (existing.isEmpty()) {
            if (expectedVersion != null) {
                throw new StalePostmortemException(incidentId);
            }
            postmortem = Postmortem.writeFor(incident, content, actor, now());
        } else {
            postmortem = existing.get();
            if (expectedVersion == null) {
                throw new StalePostmortemException(incidentId);
            }
            postmortem.requireVersion(expectedVersion);
            postmortem.revise(content, actor, now());
        }
        try {
            return postmortems.saveAndFlush(postmortem);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw new StalePostmortemException(incidentId);
        }
    }

    /** Only finished uploads, oldest first. */
    public List<Attachment> attachments(UUID incidentId) {
        Incident incident = incident(incidentId);
        return attachments.findByIncidentIdAndStatusOrderByCreatedAtAsc(incident.getId(), AttachmentStatus.UPLOADED);
    }

    /** Checks the file against {@link AttachmentPolicy}, records it as pending and signs an upload URL for it. */
    @Transactional
    public UploadTicket announceAttachment(UUID incidentId, String fileName, String contentType, long sizeBytes,
                                           String actor) {
        Incident incident = incident(incidentId);
        String type = AttachmentPolicy.normalize(contentType);
        AttachmentPolicy.check(type, sizeBytes);
        Attachment attachment = attachments.save(
                Attachment.announce(incident, fileName, type, sizeBytes, actor, now()));
        return new UploadTicket(attachment, storage.presignUpload(attachment));
    }

    /**
     * Called by the client after its upload. The signed URL already pins the type and size, but this is
     * where the server confirms it: whatever S3 holds must match what was announced, or it is deleted.
     * Safe to repeat. Not rolled back on rejection, so the deleted object's row goes too.
     */
    @Transactional(noRollbackFor = AttachmentRejectedException.class)
    public Attachment completeUpload(UUID incidentId, UUID attachmentId) {
        Attachment attachment = attachment(incidentId, attachmentId);
        if (attachment.getStatus() == AttachmentStatus.UPLOADED) {
            return attachment;
        }
        StoredObject stored = storage.find(attachment.getObjectKey())
                .orElseThrow(() -> new UploadNotFinishedException(attachmentId));
        if (stored.sizeBytes() != attachment.getSizeBytes()
                || !attachment.getContentType().equals(AttachmentPolicy.normalize(stored.contentType()))) {
            storage.delete(attachment.getObjectKey());
            attachments.delete(attachment);
            throw new AttachmentRejectedException(
                    "The uploaded file does not match the one announced, so it was discarded. Upload it again.");
        }
        attachment.markUploaded(now());
        return attachment;
    }

    public PresignedDownload downloadUrl(UUID incidentId, UUID attachmentId) {
        Attachment attachment = attachment(incidentId, attachmentId);
        if (attachment.getStatus() != AttachmentStatus.UPLOADED) {
            throw new AttachmentNotFoundException(incidentId, attachmentId);
        }
        return storage.presignDownload(attachment);
    }

    private Incident incident(UUID id) {
        return incidents.findById(id).orElseThrow(() -> new IncidentNotFoundException(id));
    }

    private Attachment attachment(UUID incidentId, UUID attachmentId) {
        return attachments.findByIdAndIncidentId(attachmentId, incidentId)
                .orElseThrow(() -> new AttachmentNotFoundException(incidentId, attachmentId));
    }

    /** Microsecond precision, as in {@link IncidentService}. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
