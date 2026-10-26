package dev.beacon.alerts.ingest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Entry point for {@code POST /alerts} behind an API Gateway REST API (Lambda proxy integration).
 *
 * <p>Configuration comes from the environment: {@code ALERT_QUEUE_URL} and {@code INGEST_API_KEY}.
 * The SQS client is built once per container, outside the handler, so warm invocations reuse it.
 * Region and credentials come from the Lambda runtime; under LocalStack, {@code AWS_ENDPOINT_URL}
 * (set by LocalStack) points the SDK at the emulator without any code change.
 */
public class AlertIngestHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    static final String KEY_HEADER = "X-Beacon-Ingest-Key";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AlertIngestor ingestor;

    /** Used by the Lambda runtime. */
    public AlertIngestHandler() {
        this(new AlertIngestor(System.getenv("INGEST_API_KEY"), new SqsAlertPublisher(
                SqsClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                System.getenv("ALERT_QUEUE_URL"))));
    }

    AlertIngestHandler(AlertIngestor ingestor) {
        this.ingestor = ingestor;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        AlertIngestor.Outcome outcome = isJson(header(request, "Content-Type"))
                ? ingestor.ingest(header(request, KEY_HEADER), body(request), receivedAt(request))
                : new AlertIngestor.Outcome.Rejected(415, "Unsupported media type",
                        "Send the alert as JSON with Content-Type: application/json.");
        return switch (outcome) {
            case AlertIngestor.Outcome.Accepted accepted -> {
                context.getLogger().log("Queued alert " + accepted.alertId() + " fingerprint=" + accepted.fingerprint());
                yield response(202, "application/json",
                        Map.of("alertId", accepted.alertId().toString(), "fingerprint", accepted.fingerprint()));
            }
            case AlertIngestor.Outcome.Rejected rejected -> {
                context.getLogger().log("Rejected alert: " + rejected.status() + " " + rejected.detail());
                yield response(rejected.status(), "application/problem+json",
                        Map.of("status", rejected.status(), "title", rejected.title(), "detail", rejected.detail()));
            }
        };
    }

    /** Anything else (e.g. curl's default form encoding) may reach us altered, so it is refused up front. */
    private static boolean isJson(String contentType) {
        return contentType != null && contentType.strip().toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    /** HTTP header names are case-insensitive, and HTTP/2 clients send them in lower case. */
    private static String header(APIGatewayProxyRequestEvent request, String name) {
        if (request.getHeaders() == null) {
            return null;
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(request.getHeaders());
        return headers.get(name);
    }

    private static String body(APIGatewayProxyRequestEvent request) {
        String body = request.getBody();
        if (body != null && Boolean.TRUE.equals(request.getIsBase64Encoded())) {
            return new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        return body;
    }

    /** API Gateway's own receive time, so the latency measured downstream includes this Lambda. */
    private static Instant receivedAt(APIGatewayProxyRequestEvent request) {
        var requestContext = request.getRequestContext();
        if (requestContext != null && requestContext.getRequestTimeEpoch() != null) {
            return Instant.ofEpochMilli(requestContext.getRequestTimeEpoch());
        }
        return Instant.now();
    }

    private static APIGatewayProxyResponseEvent response(int status, String contentType, Map<String, ?> body) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(status)
                    .withHeaders(Map.of("Content-Type", contentType))
                    .withBody(JSON.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
