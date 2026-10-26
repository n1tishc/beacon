package dev.beacon.alerts.ingest;

/**
 * The JSON body a monitoring tool POSTs to {@code /alerts}. Only {@code source}, {@code title} and
 * {@code severity} are required. Without a {@code fingerprint}, one is derived from source and title.
 */
public record IncomingAlert(String source, String title, String description, String severity, String fingerprint) {
}
