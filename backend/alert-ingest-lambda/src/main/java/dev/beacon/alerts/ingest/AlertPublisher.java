package dev.beacon.alerts.ingest;

import dev.beacon.alerts.AlertMessage;

/** Where accepted alerts go. In AWS that is SQS; tests substitute their own. */
@FunctionalInterface
public interface AlertPublisher {

    void publish(AlertMessage message);
}
