package dev.beacon.incident.postmortem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import dev.beacon.incident.PostgresTestcontainer;
import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.service.AbandonedUploadCleanup;
import dev.beacon.incident.service.IncidentService;
import dev.beacon.security.Role;

/**
 * Postmortems and attachments over HTTP, with a real Postgres and a real (LocalStack) S3 that checks
 * presigned-URL signatures. Files are uploaded and downloaded the way a browser does it: straight to
 * the presigned URL, with a plain HTTP client and no AWS credentials.
 */
@Testcontainers
@SpringBootTest(properties = {"spring.cloud.aws.sqs.enabled=false", "beacon.notifications.enabled=false"})
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class PostmortemIntegrationTest {

    private static final String BUCKET = "test-attachments";
    private static final String LOG = "12:01 checkout-api OOMKilled\n12:02 rollback started\n";

    @Container
    @ServiceConnection
    static final LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse(System.getProperty("localstack.image", "localstack/localstack:4.14"))
                    .asCompatibleSubstituteFor("localstack/localstack"))
            .withServices(LocalStackContainer.Service.S3)
            // LocalStack skips presigned-URL signature checks unless told otherwise; these tests rely on them.
            .withEnv("S3_SKIP_SIGNATURE_VALIDATION", "0");

    private static S3Client s3;
    private static final HttpClient browser = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("beacon.attachments.bucket", () -> BUCKET);
        registry.add("beacon.attachments.public-endpoint", () -> localstack.getEndpoint().toString());
        registry.add("beacon.attachments.path-style-access", () -> "true");
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
    }

    @BeforeAll
    static void createBucket() {
        s3 = S3Client.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .forcePathStyle(true)
                .build();
        s3.createBucket(request -> request.bucket(BUCKET));
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AbandonedUploadCleanup cleanup;

    @Test
    void writeThenReviseAPostmortem() throws Exception {
        UUID id = resolvedIncident();

        mvc.perform(get("/api/incidents/{id}/postmortem", id).with(caller("val", Role.VIEWER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Postmortem not found"));

        save(id, "riley", """
                {"summary": "  Checkout down for 9 minutes  ", "rootCause": "Memory leak in 4.2.0", "impact": "  "}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.summary").value("Checkout down for 9 minutes"))
                .andExpect(jsonPath("$.impact").doesNotExist())
                .andExpect(jsonPath("$.createdBy").value("riley"));

        save(id, "avery", """
                {"version": 0, "summary": "Checkout down for 9 minutes", "actionItems": "Add a heap alert"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.rootCause").doesNotExist())
                .andExpect(jsonPath("$.createdBy").value("riley"))
                .andExpect(jsonPath("$.updatedBy").value("avery"));

        mvc.perform(get("/api/incidents/{id}/postmortem", id).with(caller("val", Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionItems").value("Add a heap alert"));
    }

    @Test
    void savingOnAnOutdatedVersionIsAConflict() throws Exception {
        UUID id = resolvedIncident();
        save(id, "riley", "{\"summary\": \"First draft\"}").andExpect(status().isOk());
        save(id, "avery", "{\"version\": 0, \"summary\": \"Avery's edit\"}").andExpect(status().isOk());

        save(id, "riley", "{\"version\": 0, \"summary\": \"Riley's edit\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Postmortem changed"));
        // Two people starting the first draft at once: the second one is told, not silently merged.
        save(id, "sam", "{\"summary\": \"Sam's first draft\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Postmortem changed"));

        mvc.perform(get("/api/incidents/{id}/postmortem", id).with(caller("val", Role.VIEWER)))
                .andExpect(jsonPath("$.summary").value("Avery's edit"));
    }

    @Test
    void openIncidentsHaveNoPostmortemOrAttachmentsYet() throws Exception {
        UUID id = incidentService.create("Still burning", null, Severity.HIGH, "riley").getId();

        save(id, "riley", "{\"summary\": \"Too early\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Incident not resolved"));
        announce(id, "notes.txt", "text/plain", 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Incident not resolved"));
    }

    @Test
    void uploadCompleteListAndDownload() throws Exception {
        UUID id = resolvedIncident();
        byte[] file = LOG.getBytes(StandardCharsets.UTF_8);

        String ticket = announce(id, "checkout \"pod\" log.txt", "text/plain; charset=utf-8", file.length)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachment.status").value("PENDING"))
                .andExpect(jsonPath("$.attachment.contentType").value("text/plain"))
                .andExpect(jsonPath("$.uploadHeaders['content-type']").value("text/plain"))
                .andReturn().getResponse().getContentAsString();
        String attachmentId = JsonPath.read(ticket, "$.attachment.id");

        // Not listed until the server has seen the file in S3.
        mvc.perform(get("/api/incidents/{id}/postmortem/attachments", id).with(caller("val", Role.VIEWER)))
                .andExpect(jsonPath("$.length()").value(0));

        assertThat(upload(ticket, Map.of(), file).statusCode()).isEqualTo(200);
        complete(id, attachmentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.uploadedAt").isNotEmpty());
        // Completing again (a retried request) changes nothing.
        complete(id, attachmentId).andExpect(status().isOk());

        mvc.perform(get("/api/incidents/{id}/postmortem/attachments", id).with(caller("val", Role.VIEWER)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fileName").value("checkout \"pod\" log.txt"))
                .andExpect(jsonPath("$[0].sizeBytes").value(file.length))
                .andExpect(jsonPath("$[0].uploadedBy").value("riley"));

        String download = mvc.perform(get("/api/incidents/{id}/postmortem/attachments/{a}/download", id, attachmentId)
                        .with(caller("val", Role.VIEWER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        HttpResponse<byte[]> response = browser.send(
                HttpRequest.newBuilder(URI.create(JsonPath.read(download, "$.url"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(file);
        assertThat(response.headers().firstValue("Content-Disposition")).hasValue(
                "attachment; filename=\"checkout _pod_ log.txt\"; filename*=UTF-8''checkout%20%22pod%22%20log.txt");
    }

    @Test
    void theUploadUrlOnlyAcceptsTheAnnouncedTypeAndSize() throws Exception {
        UUID id = resolvedIncident();
        byte[] file = LOG.getBytes(StandardCharsets.UTF_8);
        String ticket = announce(id, "log.txt", "text/plain", file.length)
                .andReturn().getResponse().getContentAsString();

        // Content-Type and Content-Length are signed: S3 itself refuses anything else.
        assertThat(upload(ticket, Map.of("content-type", "text/html"), file).statusCode()).isEqualTo(403);
        assertThat(upload(ticket, Map.of(), (LOG + LOG).getBytes(StandardCharsets.UTF_8)).statusCode())
                .isEqualTo(403);
        // And the URL is for that one key: it cannot be pointed at another object.
        String otherKey = JsonPath.<String>read(ticket, "$.uploadUrl").replace("/attachments/", "/attachments/x");
        HttpResponse<Void> retargeted = browser.send(HttpRequest.newBuilder(URI.create(otherKey))
                        .header("Content-Type", "text/plain")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(file)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(retargeted.statusCode()).isEqualTo(403);
    }

    @Test
    void completingBeforeUploadingIsAConflict() throws Exception {
        UUID id = resolvedIncident();
        String ticket = announce(id, "graph.png", "image/png", 2048).andReturn().getResponse().getContentAsString();

        complete(id, JsonPath.read(ticket, "$.attachment.id"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Upload not finished"));
    }

    /** Defence in depth: if something other than the signed URL put a different object there, it is not kept. */
    @Test
    void anObjectThatDoesNotMatchTheAnnouncementIsDiscarded() throws Exception {
        UUID id = resolvedIncident();
        String ticket = announce(id, "graph.png", "image/png", 2048).andReturn().getResponse().getContentAsString();
        String attachmentId = JsonPath.read(ticket, "$.attachment.id");
        String key = jdbc.queryForObject("select object_key from incident.attachments where id = ?::uuid",
                String.class, attachmentId);
        s3.putObject(request -> request.bucket(BUCKET).key(key).contentType("text/html"),
                RequestBody.fromString("<script>alert(1)</script>"));

        complete(id, attachmentId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Attachment not allowed"));

        assertThat(jdbc.queryForObject("select count(*) from incident.attachments where id = ?::uuid",
                Integer.class, attachmentId)).isZero();
        try {
            s3.headObject(request -> request.bucket(BUCKET).key(key));
            throw new AssertionError("the object should have been deleted");
        } catch (NoSuchKeyException expected) {
            // deleted
        }
    }

    @Test
    void disallowedFilesGetNoUploadUrl() throws Exception {
        UUID id = resolvedIncident();

        announce(id, "page.html", "text/html", 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Attachment not allowed"))
                .andExpect(jsonPath("$.uploadUrl").doesNotExist());
        announce(id, "logo.svg", "image/svg+xml", 100).andExpect(status().isBadRequest());
        announce(id, "heap.png", "image/png", 10L * 1024 * 1024 + 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Attachments are limited to 10 MB."));
        announce(id, "", "image/png", 10)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fileName"));

        assertThat(jdbc.queryForObject("select count(*) from incident.attachments where incident_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void attachmentsAreScopedToTheirIncident() throws Exception {
        UUID id = resolvedIncident();
        UUID other = resolvedIncident();
        String ticket = announce(id, "log.txt", "text/plain", LOG.length()).andReturn().getResponse().getContentAsString();
        upload(ticket, Map.of(), LOG.getBytes(StandardCharsets.UTF_8));
        String attachmentId = JsonPath.read(ticket, "$.attachment.id");

        complete(other, attachmentId).andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents/{id}/postmortem/attachments/{a}/download", other, attachmentId)
                        .with(caller("val", Role.VIEWER)))
                .andExpect(status().isNotFound());
        // Nor can a pending upload be downloaded before it was checked.
        mvc.perform(get("/api/incidents/{id}/postmortem/attachments/{a}/download", id, attachmentId)
                        .with(caller("val", Role.VIEWER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadsLeftUnfinishedAreDiscardedButNothingElse() throws Exception {
        UUID id = resolvedIncident();
        byte[] file = LOG.getBytes(StandardCharsets.UTF_8);
        // Uploaded to S3 but never completed, two hours ago.
        String abandoned = announce(id, "abandoned.txt", "text/plain", file.length)
                .andReturn().getResponse().getContentAsString();
        upload(abandoned, Map.of(), file);
        // Finished two hours ago: a real attachment, however old.
        String finished = announce(id, "finished.txt", "text/plain", file.length)
                .andReturn().getResponse().getContentAsString();
        upload(finished, Map.of(), file);
        complete(id, JsonPath.read(finished, "$.attachment.id")).andExpect(status().isOk());
        jdbc.update("update incident.attachments set created_at = now() - interval '2 hours' where incident_id = ?", id);
        // Announced just now: its URL is still valid, so it must survive.
        String inProgress = announce(id, "in-progress.txt", "text/plain", file.length)
                .andReturn().getResponse().getContentAsString();

        assertThat(cleanup.discardAbandonedUploads()).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForList("select file_name from incident.attachments where incident_id = ? order by file_name",
                String.class, id)).containsExactly("finished.txt", "in-progress.txt");
        String abandonedKey = "incidents/" + id + "/attachments/" + JsonPath.read(abandoned, "$.attachment.id");
        try {
            s3.headObject(request -> request.bucket(BUCKET).key(abandonedKey));
            throw new AssertionError("the abandoned object should have been deleted");
        } catch (NoSuchKeyException expected) {
            // deleted
        }
        assertThat(upload(inProgress, Map.of(), file).statusCode()).isEqualTo(200);
    }

    private UUID resolvedIncident() {
        Incident incident = incidentService.create("Checkout returning 502s", null, Severity.HIGH, "riley");
        incidentService.resolve(incident.getId(), 0, "riley", "Rolled back");
        return incident.getId();
    }

    private ResultActions save(UUID id, String username, String body) throws Exception {
        return perform(put("/api/incidents/{id}/postmortem", id), username, body);
    }

    private ResultActions announce(UUID id, String fileName, String contentType, long size) throws Exception {
        return perform(post("/api/incidents/{id}/postmortem/attachments", id), "riley", """
                {"fileName": "%s", "contentType": "%s", "sizeBytes": %d}
                """.formatted(fileName.replace("\"", "\\\""), contentType, size));
    }

    private ResultActions complete(UUID id, String attachmentId) throws Exception {
        return mvc.perform(post("/api/incidents/{id}/postmortem/attachments/{a}/complete", id, attachmentId)
                .with(caller("riley", Role.RESPONDER)));
    }

    /** PUTs to the presigned URL like a browser: the ticket's headers (optionally overridden), nothing else. */
    private static HttpResponse<Void> upload(String ticket, Map<String, String> overrides, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(JsonPath.read(ticket, "$.uploadUrl")))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        Map<String, String> headers = new java.util.HashMap<>(JsonPath.read(ticket, "$.uploadHeaders"));
        headers.putAll(overrides);
        headers.forEach(request::header);
        return browser.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String username, String body) throws Exception {
        return mvc.perform(request.with(caller(username, Role.RESPONDER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static JwtRequestPostProcessor caller(String username, Role role) {
        return jwt().jwt(token -> token.subject(username))
                .authorities(new SimpleGrantedAuthority(role.authority()));
    }
}
