package dev.beacon.incident.api;

import java.net.URI;
import java.time.Instant;

import dev.beacon.incident.storage.PresignedDownload;

/**
 * A JSON body rather than a redirect: the browser fetches this with its access token, which a plain
 * link to S3 could not carry, then follows the URL itself.
 */
public record DownloadResponse(URI url, Instant expiresAt) {

    public static DownloadResponse from(PresignedDownload download) {
        return new DownloadResponse(download.url(), download.expiresAt());
    }
}
