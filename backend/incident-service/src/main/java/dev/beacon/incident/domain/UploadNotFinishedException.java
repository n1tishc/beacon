package dev.beacon.incident.domain;

import java.util.UUID;

/** The client said an upload was done, but nothing is in S3 under that attachment's key. Mapped to 409. */
public class UploadNotFinishedException extends RuntimeException {

    public UploadNotFinishedException(UUID attachmentId) {
        super("Nothing has been uploaded for attachment " + attachmentId + " yet. Upload the file, then try again.");
    }
}
