package dev.beacon.incident.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Everything a browser needs to PUT one file into S3. The headers were signed with the URL, so the
 * request must carry exactly these values (Content-Type in particular), or S3 rejects it.
 */
public record PresignedUpload(URI url, Map<String, String> headers, Instant expiresAt) {
}
