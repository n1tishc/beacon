package dev.beacon.incident.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

import dev.beacon.alerts.AlertMessage;
import dev.beacon.alerts.AlertMessageCodec;
import dev.beacon.alerts.AlertSeverity;
import dev.beacon.incident.PostgresTestcontainer;
import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentRepository;
import dev.beacon.incident.domain.IncidentStatus;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.domain.TimelineEntry;
import dev.beacon.incident.service.IncidentService;

/**
 * The SQS consumer end to end: a real queue with a dead-letter queue (LocalStack), the real listener
 * container, and a real Postgres. Messages are sent exactly as the ingest Lambda sends them.
 */
// @Testcontainers comes first so its extension runs last on teardown: the listener stops (the context is
// closed by @DirtiesContext) before LocalStack does, instead of polling a queue that is gone.
@Testcontainers
@SpringBootTest(properties = {"beacon.alerts.queue=" + AlertIngestionIntegrationTest.QUEUE, "beacon.notifications.enabled=false"})
@Import(PostgresTestcontainer.class)
@DirtiesContext
class AlertIngestionIntegrationTest {

    static final String QUEUE = "test-alerts";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Container
    @ServiceConnection
    static final LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse(System.getProperty("localstack.image", "localstack/localstack:4.14"))
                    .asCompatibleSubstituteFor("localstack/localstack"))
            .withServices(LocalStackContainer.Service.SQS);

    private static SqsClient sqs;
    private static String queueUrl;
    private static String deadLetterQueueUrl;

    @Autowired
    private IncidentRepository incidents;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private AlertListener listener;

    /**
     * Same shape as the real queue (infra/localstack/init), but with a 1 s visibility timeout and two
     * receives before the dead-letter queue, so the redelivery paths run in seconds rather than minutes.
     */
    @BeforeAll
    static void createQueues() {
        sqs = SqsClient.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();
        deadLetterQueueUrl = sqs.createQueue(request -> request.queueName(QUEUE + "-dlq")).queueUrl();
        String deadLetterArn = sqs.getQueueAttributes(request -> request.queueUrl(deadLetterQueueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        queueUrl = sqs.createQueue(request -> request.queueName(QUEUE).attributes(Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                QueueAttributeName.REDRIVE_POLICY,
                "{\"deadLetterTargetArn\":\"" + deadLetterArn + "\",\"maxReceiveCount\":\"2\"}"))).queueUrl();
    }

    @Test
    void theFirstAlertOpensAnIncidentTriggeredByItsSource() {
        AlertMessage alert = alert(newFingerprint(), AlertSeverity.CRITICAL);

        send(alert);

        UUID incidentId = awaitOpenIncident(alert.fingerprint());
        Incident incident = incidentService.get(incidentId);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.TRIGGERED);
        assertThat(incident.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(incident.getTitle()).isEqualTo(alert.title());
        assertThat(incident.getSource()).isEqualTo("prometheus");
        assertThat(incident.getAlertCount()).isEqualTo(1);
        assertThat(incident.getLastAlertAt()).isEqualTo(alert.receivedAt());
        assertThat(incidentService.timeline(incidentId)).singleElement()
                .extracting(TimelineEntry::getActor).isEqualTo("alert:prometheus");
        await().atMost(WAIT).until(() -> latencyCount("created") >= 1);
    }

    @Test
    void repeatsFoldIntoTheOpenIncidentWithoutMakingResponderCopiesStale() {
        String fingerprint = newFingerprint();
        send(alert(fingerprint, AlertSeverity.HIGH));
        UUID incidentId = awaitOpenIncident(fingerprint);

        send(alert(fingerprint, AlertSeverity.CRITICAL));
        send(alert(fingerprint, AlertSeverity.LOW));

        await().atMost(WAIT).until(() -> incidentService.get(incidentId).getAlertCount() == 3);
        Incident incident = incidentService.get(incidentId);
        assertThat(incident.getSeverity()).as("the first alert decides the severity").isEqualTo(Severity.HIGH);
        assertThat(incident.getVersion()).as("repeats do not bump the version").isZero();
        assertThat(incidentService.timeline(incidentId)).hasSize(1);
        assertThat(alertsWith(fingerprint)).isEqualTo(3);
    }

    @Test
    void onceResolvedTheNextAlertOpensANewIncident() {
        String fingerprint = newFingerprint();
        send(alert(fingerprint, AlertSeverity.HIGH));
        UUID first = awaitOpenIncident(fingerprint);
        incidentService.resolve(first, 0, "riley", "Fixed");

        send(alert(fingerprint, AlertSeverity.HIGH));

        UUID second = awaitOpenIncident(fingerprint);
        assertThat(second).isNotEqualTo(first);
    }

    /** SQS standard queues deliver at least once; a redelivery must not count the alert twice. */
    @Test
    void aRedeliveredAlertIsProcessedOnce() {
        AlertMessage alert = alert(newFingerprint(), AlertSeverity.MEDIUM);
        double alreadyProcessedBefore = latencyCount("already_processed");

        send(alert);
        send(alert);

        await().atMost(WAIT).until(() -> latencyCount("already_processed") > alreadyProcessedBefore);
        assertThat(incidentService.get(awaitOpenIncident(alert.fingerprint())).getAlertCount()).isEqualTo(1);
        assertThat(alertsWith(alert.fingerprint())).isEqualTo(1);
    }

    /** The listener processes up to ten messages at once, so these genuinely race to open the incident. */
    @Test
    void twentyConcurrentAlertsForOneProblemOpenExactlyOneIncident() {
        String fingerprint = newFingerprint();
        List<AlertMessage> burst = IntStream.range(0, 20).mapToObj(i -> alert(fingerprint, AlertSeverity.HIGH)).toList();

        for (int batch = 0; batch < burst.size(); batch += 10) {
            List<SendMessageBatchRequestEntry> entries = burst.subList(batch, batch + 10).stream()
                    .map(alert -> SendMessageBatchRequestEntry.builder().id(alert.alertId().toString())
                            .messageBody(AlertMessageCodec.toJson(alert)).build())
                    .toList();
            sqs.sendMessageBatch(request -> request.queueUrl(queueUrl).entries(entries));
        }

        await().atMost(WAIT).until(() -> alertsWith(fingerprint) == 20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident.incidents WHERE fingerprint = ?",
                Integer.class, fingerprint)).isEqualTo(1);
        assertThat(incidentService.get(awaitOpenIncident(fingerprint)).getAlertCount()).isEqualTo(20);
    }

    /**
     * The same race without SQS's scheduling in the way: eight deliveries released at the same instant.
     * Several see "no open incident" and try to open one; the unique index lets one win and the
     * listener's retry folds the rest into it.
     */
    @Test
    void simultaneousDeliveriesRaceSafelyThroughTheRetry() throws Exception {
        String fingerprint = newFingerprint();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> deliveries = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                String body = AlertMessageCodec.toJson(alert(fingerprint, AlertSeverity.HIGH));
                deliveries.add(pool.submit(() -> {
                    start.await();
                    listener.onMessage(body);
                    return null;
                }));
            }
            start.countDown();
        }
        for (Future<?> delivery : deliveries) {
            delivery.get(); // rethrows if any delivery failed even after its retry
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident.incidents WHERE fingerprint = ?",
                Integer.class, fingerprint)).isEqualTo(1);
        assertThat(incidentService.get(awaitOpenIncident(fingerprint)).getAlertCount()).isEqualTo(8);
    }

    @Test
    void aMessageThatCannotBeProcessedEndsUpInTheDeadLetterQueue() {
        sqs.sendMessage(request -> request.queueUrl(queueUrl).messageBody("{\"this is\": \"not an alert\"}"));

        await().atMost(WAIT).until(() -> !sqs.receiveMessage(request -> request.queueUrl(deadLetterQueueUrl)
                .waitTimeSeconds(1)).messages().isEmpty());
    }

    private static void send(AlertMessage alert) {
        sqs.sendMessage(request -> request.queueUrl(queueUrl).messageBody(AlertMessageCodec.toJson(alert)));
    }

    private UUID awaitOpenIncident(String fingerprint) {
        return await().atMost(WAIT).until(() -> incidents.findOpenIncidentIdByFingerprint(fingerprint),
                found -> found.isPresent()).orElseThrow();
    }

    private int alertsWith(String fingerprint) {
        return jdbc.queryForObject("SELECT count(*) FROM incident.alerts WHERE fingerprint = ?", Integer.class,
                fingerprint);
    }

    private double latencyCount(String outcome) {
        Timer timer = meters.find(AlertListener.LATENCY_METRIC).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private static String newFingerprint() {
        return "fp-" + UUID.randomUUID();
    }

    private static AlertMessage alert(String fingerprint, AlertSeverity severity) {
        return new AlertMessage(UUID.randomUUID(), "prometheus", fingerprint, "Checkout p99 above 2s",
                "From the integration test", severity, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
