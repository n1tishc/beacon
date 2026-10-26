package dev.beacon.incident.notify;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;

import dev.beacon.alerts.AlertMessage;
import dev.beacon.alerts.AlertSeverity;
import dev.beacon.incident.PostgresTestcontainer;
import dev.beacon.incident.ingest.AlertIngestionService;
import dev.beacon.security.Role;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * incident-service against a WireMock server playing notification-service: when it works, when it
 * fails, and when it hangs. Whatever notification-service does, incidents are created and the API
 * answers at once. Resilience settings are shortened so the tests run in seconds.
 */
@SpringBootTest(properties = {
        "spring.cloud.aws.sqs.enabled=false",
        "beacon.notifications.service-token=" + NotificationServiceDownIntegrationTest.SERVICE_TOKEN,
        "resilience4j.timelimiter.instances.notification-service.timeout-duration=500ms",
        "resilience4j.retry.instances.notification-service.wait-duration=20ms"
})
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class NotificationServiceDownIntegrationTest {

    static final String SERVICE_TOKEN = "test-service-token";
    private static final String PATH = "/internal/notifications";

    @RegisterExtension
    static WireMockExtension notificationService = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void pointAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("NOTIFICATION_SERVICE_URL", notificationService::baseUrl);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AlertIngestionService alertIngestion;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    private CircuitBreaker circuit;

    @BeforeEach
    void setUp() {
        circuit = circuitBreakers.circuitBreaker(NotificationClient.INSTANCE);
        circuit.reset();
    }

    @Test
    void eachChangeIsSentAfterCommitWithTheServiceTokenButNotesAreNot() throws Exception {
        notificationService.stubFor(post(PATH).willReturn(ok("[]")));

        String id = trigger("Checkout returning 502s");
        command(id, "acknowledge", "{\"version\": 0}");
        command(id, "notes", "{\"text\": \"Rolling back\"}");
        command(id, "resolve", "{\"version\": 1}");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                notificationService.verify(3, postRequestedFor(urlEqualTo(PATH))));
        Thread.sleep(200); // a fourth (the note) would have arrived by now
        notificationService.verify(3, postRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Beacon-Service-Token", equalTo(SERVICE_TOKEN)));

        // The exact body notification-service's IncidentEventRequest reads.
        LoggedRequest triggered = notificationService.findAll(postRequestedFor(urlEqualTo(PATH))).stream()
                .filter(request -> request.getBodyAsString().contains("\"TRIGGERED\""))
                .findFirst().orElseThrow();
        String body = triggered.getBodyAsString();
        assertThat(JsonPath.<Integer>read(body, "$.eventId")).isPositive();
        notificationService.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"eventId": "${json-unit.any-number}", "incidentId": "%s", "type": "TRIGGERED",
                 "title": "Checkout returning 502s", "severity": "HIGH", "actor": "riley",
                 "occurredAt": "${json-unit.any-string}"}
                """.formatted(id))));
    }

    /** The spec's claim from this side: notification-service failing does not stop incidents. */
    @Test
    void incidentsAreCreatedWhileNotificationServiceFailsAndTheNotificationIsDroppedAfterRetries() throws Exception {
        notificationService.stubFor(post(PATH).willReturn(serverError()));
        double droppedBefore = dropped();

        String id = trigger("Payments API timing out");

        mvc.perform(MockMvcRequestBuilders.get("/api/incidents/{id}", id).with(caller(Role.VIEWER)))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5)).until(() -> dropped() == droppedBefore + 1);
        notificationService.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aHangingNotificationServiceDoesNotDelayTheApi() throws Exception {
        notificationService.stubFor(post(PATH).willReturn(ok("[]").withFixedDelay(5_000)));
        double droppedBefore = dropped();

        trigger("Search index lagging");

        // A synchronous call would only return after all three 500 ms attempts had timed out and the
        // notification was dropped. The response came first: the attempts run in the background.
        assertThat(dropped()).isEqualTo(droppedBefore);
        await().atMost(Duration.ofSeconds(5)).until(() -> dropped() == droppedBefore + 1);
    }

    @Test
    void afterRepeatedFailuresTheCircuitOpensAndNotificationServiceIsLeftAlone() throws Exception {
        notificationService.stubFor(post(PATH).willReturn(serverError()));
        double droppedBefore = dropped();

        // 2 changes x 3 attempts: the 5th call reaches minimum-number-of-calls with every call failed.
        trigger("Queue backlog");
        await().atMost(Duration.ofSeconds(5)).until(() -> dropped() == droppedBefore + 1);
        trigger("Queue backlog, again");
        await().atMost(Duration.ofSeconds(5)).until(() -> dropped() == droppedBefore + 2);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        notificationService.verify(5, postRequestedFor(urlEqualTo(PATH)));

        trigger("Queue backlog, third time");
        await().atMost(Duration.ofSeconds(5)).until(() -> dropped() == droppedBefore + 3);
        notificationService.verify(5, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aRejectedRequestIsNotRetried() throws Exception {
        notificationService.stubFor(post(PATH).willReturn(aResponse().withStatus(401)));
        double droppedBefore = dropped();

        trigger("Wrong service token somewhere");

        await().atMost(Duration.ofSeconds(5)).until(() -> dropped() == droppedBefore + 1);
        notificationService.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void anAlertThatOpensAnIncidentNotifiesButItsRepeatsDoNot() throws Exception {
        notificationService.stubFor(post(PATH).willReturn(ok("[]")));
        String fingerprint = "fp-" + UUID.randomUUID();

        alertIngestion.ingest(alert(fingerprint));
        alertIngestion.ingest(alert(fingerprint));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                notificationService.verify(1, postRequestedFor(urlEqualTo(PATH))
                        .withRequestBody(equalToJson("{\"actor\": \"alert:prometheus\", \"type\": \"TRIGGERED\"}", true, true))));
        Thread.sleep(200);
        notificationService.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    private double dropped() {
        return meters.counter(NotificationClient.DROPPED_METRIC).count();
    }

    private String trigger(String title) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.post("/api/incidents").with(caller(Role.RESPONDER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"" + title + "\", \"severity\": \"HIGH\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void command(String id, String command, String json) throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/incidents/{id}/" + command, id).with(caller(Role.RESPONDER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is2xxSuccessful());
    }

    private static JwtRequestPostProcessor caller(Role role) {
        return jwt().jwt(token -> token.subject("riley")).authorities(new SimpleGrantedAuthority(role.authority()));
    }

    private static AlertMessage alert(String fingerprint) {
        return new AlertMessage(UUID.randomUUID(), "prometheus", fingerprint, "Disk 95% full", null,
                AlertSeverity.HIGH, Instant.now());
    }
}
