package dev.beacon.incident.domain;

public enum AttachmentStatus {
    /** An upload URL was issued; the file may or may not have reached S3 yet. */
    PENDING,
    /** The server checked the object in S3 and it matches what was announced. */
    UPLOADED
}
