package dev.beacon.incident.storage;

import java.net.URI;
import java.time.Instant;

public record PresignedDownload(URI url, Instant expiresAt) {
}
