package dev.beacon.incident.domain;

import java.util.Locale;
import java.util.Set;

/**
 * What may be attached to a postmortem: the kinds of file an investigation produces, up to 10 MiB.
 * Deliberately no HTML, SVG or other types a browser would run script in. The frontend mirrors this
 * list to fail fast; this is the check that counts.
 */
public final class AttachmentPolicy {

    public static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp",
            "application/pdf", "text/plain", "text/csv", "application/json");

    private AttachmentPolicy() {
    }

    /** Lower case and without parameters, so {@code Text/Plain; charset=utf-8} is checked as {@code text/plain}. */
    public static String normalize(String contentType) {
        int parameters = contentType.indexOf(';');
        String type = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return type.strip().toLowerCase(Locale.ROOT);
    }

    /** Throws {@link AttachmentRejectedException} unless a file of this type and size may be attached. */
    public static void check(String contentType, long sizeBytes) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new AttachmentRejectedException("Files of type " + contentType
                    + " cannot be attached. Attach images, PDFs, or text, CSV or JSON files.");
        }
        if (sizeBytes > MAX_SIZE_BYTES) {
            throw new AttachmentRejectedException("Attachments are limited to 10 MB.");
        }
    }
}
