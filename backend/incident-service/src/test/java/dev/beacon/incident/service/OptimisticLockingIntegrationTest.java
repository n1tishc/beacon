package dev.beacon.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import dev.beacon.incident.PostgresTestcontainer;
import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentRepository;
import dev.beacon.incident.domain.IncidentStatus;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.domain.StaleIncidentException;
import dev.beacon.incident.domain.TimelineEntryType;

/**
 * Concurrent commands against a real Postgres. Two layers protect an incident: the service compares
 * the caller's version first, and Hibernate's {@code @Version} check catches anyone who slipped past
 * that comparison before the other transaction committed.
 */
// These tests are about the HTTP API and the database; the SQS listener has its own test.
@SpringBootTest(properties = "spring.cloud.aws.sqs.enabled=false")
@Import(PostgresTestcontainer.class)
class OptimisticLockingIntegrationTest {

    private static final int RESPONDERS = 8;

    @Autowired
    private IncidentService service;

    @Autowired
    private IncidentRepository incidents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void whenEightRespondersAcknowledgeAtOnceExactlyOneWins() throws Exception {
        UUID id = service.create("Payments API down", null, Severity.CRITICAL, "alerting").getId();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> attempts = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(RESPONDERS)) {
            for (int i = 0; i < RESPONDERS; i++) {
                String responder = "responder-" + i;
                attempts.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.acknowledge(id, 0, responder);
                        return null;
                    } catch (StaleIncidentException | ObjectOptimisticLockingFailureException conflict) {
                        return conflict;
                    }
                }));
            }
            start.countDown();
        }

        List<Throwable> conflicts = new ArrayList<>();
        for (Future<Throwable> attempt : attempts) {
            if (attempt.get() != null) {
                conflicts.add(attempt.get());
            }
        }
        assertThat(conflicts).hasSize(RESPONDERS - 1);
        assertThat(service.get(id).getVersion()).isEqualTo(1);
        assertThat(service.timeline(id))
                .filteredOn(entry -> entry.getType() == TimelineEntryType.ACKNOWLEDGED)
                .hasSize(1);
    }

    /**
     * Deterministic version of the race above: a transaction that has already read version 0 tries to
     * commit after another transaction committed version 1. Only the {@code @Version} column can stop it.
     */
    @Test
    void theVersionColumnRejectsAnUpdateBasedOnARowThatChangedUnderneath() {
        UUID id = service.create("Search index lagging", null, Severity.MEDIUM, "alerting").getId();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate concurrent = new TransactionTemplate(transactionManager);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> outer.executeWithoutResult(transaction -> {
            Incident staleCopy = incidents.findById(id).orElseThrow();
            concurrent.executeWithoutResult(other -> service.acknowledge(id, 0, "riley"));

            staleCopy.resolve("avery", null, Instant.now());
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Incident current = service.get(id);
        assertThat(current.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(current.getVersion()).isEqualTo(1);
    }
}
