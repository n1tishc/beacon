package dev.beacon.alerts;

/**
 * Size limits the Lambda enforces before an alert is queued. They match incident-service's columns,
 * so a message that made it onto the queue always fits the database.
 */
public final class AlertLimits {

    /** The incident timeline records the actor as {@code alert:<source>} in a 64-character column. */
    public static final int SOURCE_MAX = 48;
    public static final int TITLE_MAX = 200;
    public static final int DESCRIPTION_MAX = 4000;
    public static final int FINGERPRINT_MAX = 128;

    private AlertLimits() {
    }
}
