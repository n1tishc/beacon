package dev.beacon.incident.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.beacon.incident.domain.Attachment;
import dev.beacon.incident.domain.AttachmentRepository;
import dev.beacon.incident.domain.AttachmentStatus;
import dev.beacon.incident.storage.AttachmentStorage;
import dev.beacon.incident.storage.StorageProperties;

/**
 * An upload that was announced but never completed (the tab was closed, the PUT failed) leaves a
 * pending row and perhaps an object in S3 that nobody can see. This discards both once the upload is
 * long past the point where it could still be finished.
 *
 * <p>Only pending uploads are touched, so an age-based rule can never remove a real attachment. If
 * S3 is unreachable, nothing is deleted and the next run tries again.
 */
@Component
public class AbandonedUploadCleanup {

    private static final Logger log = LoggerFactory.getLogger(AbandonedUploadCleanup.class);

    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final StorageProperties properties;
    private final Clock clock;

    public AbandonedUploadCleanup(AttachmentRepository attachments, AttachmentStorage storage,
                                  StorageProperties properties, Clock clock) {
        this.attachments = attachments;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    /** Returns how many uploads were discarded. */
    @Scheduled(fixedDelayString = "${beacon.attachments.cleanup-interval}",
            initialDelayString = "${beacon.attachments.cleanup-interval}")
    @Transactional
    public int discardAbandonedUploads() {
        Instant cutoff = clock.instant().minus(properties.abandonedUploadAfter());
        List<Attachment> abandoned = attachments.findByStatusAndCreatedAtBefore(AttachmentStatus.PENDING, cutoff);
        for (Attachment attachment : abandoned) {
            // Deleting a key that was never uploaded is not an error in S3.
            storage.delete(attachment.getObjectKey());
            attachments.delete(attachment);
        }
        if (!abandoned.isEmpty()) {
            log.info("Discarded {} abandoned attachment upload(s)", abandoned.size());
        }
        return abandoned.size();
    }
}
