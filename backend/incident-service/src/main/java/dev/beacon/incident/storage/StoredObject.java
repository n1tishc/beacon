package dev.beacon.incident.storage;

/** What S3 says it holds under a key. */
public record StoredObject(long sizeBytes, String contentType) {
}
