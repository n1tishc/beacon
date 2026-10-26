package dev.beacon.incident.domain;

/** The file is not one Beacon accepts, or the upload did not match what was announced. Mapped to 400. */
public class AttachmentRejectedException extends RuntimeException {

    public AttachmentRejectedException(String message) {
        super(message);
    }
}
