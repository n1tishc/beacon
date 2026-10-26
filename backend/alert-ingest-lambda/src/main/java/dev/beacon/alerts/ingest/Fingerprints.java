package dev.beacon.alerts.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The default notion of "the same alert": same source, same title, ignoring case and spacing.
 * Senders that know better (e.g. Alertmanager's group key) pass their own fingerprint.
 */
final class Fingerprints {

    private Fingerprints() {
    }

    static String of(String source, String title) {
        String canonical = normalize(source) + "\n" + normalize(title);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
