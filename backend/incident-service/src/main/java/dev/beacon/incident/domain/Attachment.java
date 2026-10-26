package dev.beacon.incident.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * A file attached to an incident's postmortem: a screenshot, a log excerpt, a graph. The bytes go
 * straight from the browser to S3; this row records what was announced and whether it arrived.
 */
@Entity
@Table(name = "attachments")
public class Attachment implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    /** Chosen here, never from the file name, so an upload can only ever land under its incident's prefix. */
    @Column(name = "object_key", nullable = false, updatable = false, length = 200)
    private String objectKey;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AttachmentStatus status;

    @Column(name = "uploaded_by", nullable = false, updatable = false, length = 64)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    /** The id is assigned up front; this makes {@code save} insert instead of SELECT-then-insert. */
    @Transient
    private boolean isNew = true;

    protected Attachment() {
        // required by JPA
    }

    private Attachment(Incident incident, String fileName, String contentType, long sizeBytes,
                       String uploadedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.incidentId = incident.getId();
        this.objectKey = "incidents/" + incidentId + "/attachments/" + id;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.status = AttachmentStatus.PENDING;
        this.uploadedBy = uploadedBy;
        this.createdAt = now;
    }

    /** Announces an upload. Like the postmortem itself, only for a resolved incident. */
    public static Attachment announce(Incident incident, String fileName, String contentType, long sizeBytes,
                                      String uploadedBy, Instant now) {
        incident.requireResolved();
        return new Attachment(incident, fileName, contentType, sizeBytes, uploadedBy, now);
    }

    public void markUploaded(Instant now) {
        status = AttachmentStatus.UPLOADED;
        uploadedAt = now;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public AttachmentStatus getStatus() {
        return status;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
