package dev.beacon.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import dev.beacon.security.Role;
import dev.beacon.security.test.TestTokens;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * The real service (Postgres, Resilience4j from application.yml, security) against a WireMock
 * server playing Slack. The resilience settings are shortened so the tests run in seconds; the
 * behaviour under test is the same.
 */
@SpringBootTest(properties = {
        "beacon.notifications.service-token=" + SlackOutageIntegrationTest.SERVICE_TOKEN,
        "resilience4j.timelimiter.instances.slack.timeout-duration=300ms",
        "resilience4j.retry.instances.slack.wait-duration=20ms",
        "resilience4j.circuitbreaker.instances.slack.wait-duration-in-open-state=2s"
})
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class SlackOutageIntegrationTest {

    static final String SERVICE_TOKEN = "test-service-token";
    private static final String WEBHOOK_PATH = "/services/T000/B000/not-a-real-secret";
    private static final TestTokens tokens = TestTokens.withNewKey();

    @RegisterExtension
    static WireMockExtension slack = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension authService = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void pointAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("SLACK_WEBHOOK_URL", () -> slack.baseUrl() + WEBHOOK_PATH);
        registry.add("AUTH_JWKS_URI", () -> authService.baseUrl() + "/.well-known/jwks.json");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    private CircuitBreaker slackCircuit;

    @BeforeEach
    void setUp() {
        authService.stubFor(get("/.well-known/jwks.json").willReturn(okJson(tokens.jwksJson())));
        slackCircuit = circuitBreakers.circuitBreaker("slack");
        slackCircuit.reset();
    }

    @Test
    void deliversToSlackAndTheMockSink() throws Exception {
        slack.stubFor(post(WEBHOOK_PATH).willReturn(ok("ok")));
        UUID incidentId = UUID.randomUUID();

        sendEvent(TestEvents.json(TestEvents.newEventId(), incidentId, "TRIGGERED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].channel").value("SLACK"))
                .andExpect(jsonPath("$[1].status").value("SENT"))
                .andExpect(jsonPath("$[1].attempts").value(1))
                .andExpect(jsonPath("$[0].channel").value("MOCK"))
                .andExpect(jsonPath("$[0].status").value("SENT"));

        slack.verify(postRequestedFor(urlEqualTo(WEBHOOK_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing("[HIGH] Checkout is down")))
                .withRequestBody(matchingJsonPath("$.text", containing("/incidents/" + incidentId))));
    }

    @Test
    void retriesAServerErrorAndThenSucceeds() throws Exception {
        slack.stubFor(post(WEBHOOK_PATH).inScenario("blip").whenScenarioStateIs(STARTED)
                .willReturn(serverError()).willSetStateTo("recovered"));
        slack.stubFor(post(WEBHOOK_PATH).inScenario("blip").whenScenarioStateIs("recovered")
                .willReturn(ok("ok")));

        sendEvent(TestEvents.triggered())
                .andExpect(jsonPath("$[1].status").value("SENT"))
                .andExpect(jsonPath("$[1].attempts").value(2));
    }

    /** The spec's claim from this side: with Slack down, the request still succeeds and says what happened. */
    @Test
    void whenSlackIsDownTheNotificationIsRecordedAsFailedAndTheMockSinkStillGetsIt() throws Exception {
        slack.stubFor(post(WEBHOOK_PATH).willReturn(serverError()));

        sendEvent(TestEvents.triggered())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].channel").value("SLACK"))
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].attempts").value(3))
                .andExpect(jsonPath("$[1].detail").value("Slack answered 500"))
                .andExpect(jsonPath("$[0].channel").value("MOCK"))
                .andExpect(jsonPath("$[0].status").value("SENT"));
    }

    @Test
    void aSlowSlackTimesOutAndCountsAsAFailure() throws Exception {
        slack.stubFor(post(WEBHOOK_PATH).willReturn(ok("ok").withFixedDelay(1_000)));

        sendEvent(TestEvents.triggered())
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].attempts").value(3))
                .andExpect(jsonPath("$[1].detail").value("Slack did not answer within 300ms"));

        assertThat(slackCircuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
    }

    @Test
    void aRejectionIsNotRetriedAndDoesNotCountAgainstTheCircuit() throws Exception {
        slack.stubFor(post(WEBHOOK_PATH).willReturn(aResponse().withStatus(404).withBody("no_service")));

        sendEvent(TestEvents.triggered())
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].attempts").value(1))
                .andExpect(jsonPath("$[1].detail").value("Slack rejected the message (404): no_service"));

        assertThat(slackCircuit.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void afterRepeatedFailuresTheCircuitOpensAndSlackIsNoLongerCalled() throws Exception {
        slack.stubFor(post(WEBHOOK_PATH).willReturn(serverError()));

        // The circuit needs 4 calls before it judges (minimum-number-of-calls): the first event makes 3,
        // the second event's first attempt is the 4th. All failed, so it opens and cuts the retries short.
        sendEvent(TestEvents.triggered())
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].attempts").value(3));
        sendEvent(TestEvents.triggered())
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].attempts").value(1))
                .andExpect(jsonPath("$[1].detail").value("Slack circuit breaker opened during retries"));
        assertThat(slackCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        sendEvent(TestEvents.triggered())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].status").value("SKIPPED"))
                .andExpect(jsonPath("$[1].attempts").value(0))
                .andExpect(jsonPath("$[0].status").value("SENT"));

        slack.verify(4, postRequestedFor(urlEqualTo(WEBHOOK_PATH)));

        // The open circuit is visible to users, and through Actuator, without marking the service down.
        String viewer = tokens.forRole(Role.VIEWER);
        mvc.perform(MockMvcRequestBuilders.get("/api/notifications/channels").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer))
                .andExpect(jsonPath("$[1].channel").value("SLACK"))
                .andExpect(jsonPath("$[1].circuitState").value("OPEN"));
        mvc.perform(MockMvcRequestBuilders.get("/actuator/circuitbreakers").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer))
                .andExpect(jsonPath("$.circuitBreakers.slack.state").value("OPEN"));
        mvc.perform(MockMvcRequestBuilders.get("/actuator/health").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.circuitBreakers.details.slack.status").value("CIRCUIT_OPEN"));
    }

    @Test
    void anOpenCircuitLetsTrialCallsThroughAndClosesWhenSlackRecovers() throws Exception {
        slack.stubFor(post(WEBHOOK_PATH).willReturn(serverError()));
        sendEvent(TestEvents.triggered());
        sendEvent(TestEvents.triggered());
        assertThat(slackCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        slack.stubFor(post(WEBHOOK_PATH).willReturn(ok("ok")));
        await().atMost(Duration.ofSeconds(5))
                .until(() -> slackCircuit.getState() == CircuitBreaker.State.HALF_OPEN);

        // Two successful trial calls (permitted-number-of-calls-in-half-open-state) close it.
        sendEvent(TestEvents.triggered()).andExpect(jsonPath("$[1].status").value("SENT"));
        sendEvent(TestEvents.triggered()).andExpect(jsonPath("$[1].status").value("SENT"));
        assertThat(slackCircuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aRepeatedEventIsAnsweredFromTheRecordAndNotSentAgain() throws Exception {
        slack.stubFor(post(WEBHOOK_PATH).willReturn(ok("ok")));
        String event = TestEvents.triggered();

        String first = sendEvent(event).andReturn().getResponse().getContentAsString();
        String second = sendEvent(event).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        slack.verify(1, postRequestedFor(urlEqualTo(WEBHOOK_PATH)));
    }

    @Test
    void usersCanListAnIncidentsNotificationsOldestFirst() throws Exception {
        slack.stubFor(post(WEBHOOK_PATH).willReturn(ok("ok")));
        UUID incidentId = UUID.randomUUID();
        sendEvent(TestEvents.json(TestEvents.newEventId(), incidentId, "TRIGGERED"));
        sendEvent(TestEvents.json(TestEvents.newEventId(), incidentId, "ACKNOWLEDGED"));

        mvc.perform(MockMvcRequestBuilders.get("/api/notifications").param("incidentId", incidentId.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forRole(Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].eventType").value("TRIGGERED"))
                .andExpect(jsonPath("$[2].eventType").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$[2].message").value(containsString("alert:datadog acknowledged: Checkout is down")));
    }

    @Test
    void anInvalidEventIsRejectedWithAProblem() throws Exception {
        sendEvent("{\"eventId\": 1, \"type\": \"TRIGGERED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));

        slack.verify(0, postRequestedFor(urlEqualTo(WEBHOOK_PATH)));
    }

    private ResultActions sendEvent(String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/internal/notifications")
                .header("X-Beacon-Service-Token", SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}
