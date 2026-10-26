package dev.beacon.alerts.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import dev.beacon.alerts.AlertMessage;

/** The API Gateway adapter: headers, body encoding, timestamps and the HTTP response shape. */
class AlertIngestHandlerTest {

    private static final String KEY = "s3cret-ingest-key";
    private static final String ALERT = """
            {"source": "prometheus", "title": "Disk full on db-1", "severity": "HIGH"}""";

    private final List<AlertMessage> published = new ArrayList<>();
    private final List<String> logged = new ArrayList<>();
    private final AlertIngestHandler handler = new AlertIngestHandler(new AlertIngestor(KEY, published::add));
    private final Context context = mock(Context.class);

    @BeforeEach
    void setUp() {
        LambdaLogger logger = mock(LambdaLogger.class);
        doAnswer(invocation -> logged.add(invocation.getArgument(0))).when(logger).log(anyString());
        when(context.getLogger()).thenReturn(logger);
    }

    @Test
    void acceptsAnAlertWith202AndTheAssignedId() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request(Map.of("Content-Type", "application/json", "X-Beacon-Ingest-Key", KEY), ALERT), context);

        assertThat(response.getStatusCode()).isEqualTo(202);
        assertThat(response.getHeaders()).containsEntry("Content-Type", "application/json");
        assertThat(response.getBody()).contains("\"alertId\":\"" + published.getFirst().alertId() + "\"");
    }

    @Test
    void readsHeadersRegardlessOfCase() {
        handler.handleRequest(
                request(Map.of("content-type", "application/json; charset=utf-8", "x-beacon-ingest-key", KEY), ALERT),
                context);

        assertThat(published).hasSize(1);
    }

    @Test
    void decodesBase64Bodies() {
        APIGatewayProxyRequestEvent request = request(
                Map.of("Content-Type", "application/json", "X-Beacon-Ingest-Key", KEY),
                Base64.getEncoder().encodeToString(ALERT.getBytes(StandardCharsets.UTF_8)));
        request.setIsBase64Encoded(true);

        handler.handleRequest(request, context);

        assertThat(published.getFirst().title()).isEqualTo("Disk full on db-1");
    }

    @Test
    void stampsTheAlertWithApiGatewaysReceiveTime() {
        APIGatewayProxyRequestEvent request = request(
                Map.of("Content-Type", "application/json", "X-Beacon-Ingest-Key", KEY), ALERT);
        request.setRequestContext(new ProxyRequestContext().withRequestTimeEpoch(1_768_471_200_000L));

        handler.handleRequest(request, context);

        assertThat(published.getFirst().receivedAt()).isEqualTo(Instant.ofEpochMilli(1_768_471_200_000L));
    }

    @Test
    void refusesNonJsonBodiesWith415() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request(Map.of("Content-Type", "application/x-www-form-urlencoded", "X-Beacon-Ingest-Key", KEY), ALERT),
                context);

        assertThat(response.getStatusCode()).isEqualTo(415);
        assertThat(published).isEmpty();
    }

    @Test
    void rejectionsAreProblemDetailsAndNeverLogTheKey() {
        String wrongKey = "guessed-key-123";
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request(Map.of("Content-Type", "application/json", "X-Beacon-Ingest-Key", wrongKey), ALERT), context);

        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(response.getHeaders()).containsEntry("Content-Type", "application/problem+json");
        assertThat(response.getBody()).contains("\"title\":\"Unauthorized\"");
        assertThat(logged).isNotEmpty().noneMatch(line -> line.contains(wrongKey) || line.contains(KEY));
    }

    @Test
    void handlesARequestWithoutHeaders() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(new APIGatewayProxyRequestEvent().withBody(ALERT), context);

        assertThat(response.getStatusCode()).isEqualTo(415);
    }

    private static APIGatewayProxyRequestEvent request(Map<String, String> headers, String body) {
        return new APIGatewayProxyRequestEvent().withHttpMethod("POST").withPath("/alerts")
                .withHeaders(headers).withBody(body);
    }
}
