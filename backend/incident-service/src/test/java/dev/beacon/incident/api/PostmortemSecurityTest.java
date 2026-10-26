package dev.beacon.incident.api;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import software.amazon.awssdk.core.exception.SdkClientException;

import dev.beacon.incident.TestIncidents;
import dev.beacon.incident.config.SecurityConfig;
import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.Postmortem;
import dev.beacon.incident.domain.PostmortemContent;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.service.PostmortemService;
import dev.beacon.incident.storage.PresignedDownload;
import dev.beacon.security.Role;
import dev.beacon.security.test.TestTokens;

/** Who may read and who may write postmortems, with real tokens as in {@link IncidentSecurityTest}. */
@WebMvcTest(PostmortemController.class)
@Import(SecurityConfig.class)
class PostmortemSecurityTest {

    private static final TestTokens tokens = TestTokens.withNewKey();
    private static final UUID INCIDENT = UUID.randomUUID();
    private static final UUID ATTACHMENT = UUID.randomUUID();
    private static final PostmortemContent CONTENT = new PostmortemContent("Checkout down", null, null, null);
    private static final String BASE = "/api/incidents/" + INCIDENT + "/postmortem";

    @RegisterExtension
    static WireMockExtension authService = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void jwksFromStub(DynamicPropertyRegistry registry) {
        registry.add("AUTH_JWKS_URI", () -> authService.baseUrl() + "/.well-known/jwks.json");
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PostmortemService postmortemService;

    @BeforeEach
    void setUp() {
        authService.stubFor(get("/.well-known/jwks.json").willReturn(okJson(tokens.jwksJson())));
    }

    @Test
    void requestsWithoutATokenAreUnauthorized() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void viewersCanReadAttachmentsAndDownloadThem() throws Exception {
        String viewer = tokens.forRole(Role.VIEWER);
        when(postmortemService.attachments(INCIDENT)).thenReturn(List.of());
        when(postmortemService.downloadUrl(INCIDENT, ATTACHMENT))
                .thenReturn(new PresignedDownload(URI.create("http://s3.test/file"), Instant.now()));

        mvc.perform(read(viewer, BASE + "/attachments")).andExpect(status().isOk());
        mvc.perform(read(viewer, BASE + "/attachments/" + ATTACHMENT + "/download"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("http://s3.test/file"));
    }

    @Test
    void viewersCannotWriteOrUpload() throws Exception {
        String viewer = tokens.forRole(Role.VIEWER);

        mvc.perform(write(viewer, MockMvcRequestBuilders.put(BASE), "{\"summary\": \"hi\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(write(viewer, MockMvcRequestBuilders.post(BASE + "/attachments"),
                        "{\"fileName\": \"a.txt\", \"contentType\": \"text/plain\", \"sizeBytes\": 1}"))
                .andExpect(status().isForbidden());
        mvc.perform(write(viewer, MockMvcRequestBuilders.post(BASE + "/attachments/" + ATTACHMENT + "/complete"), ""))
                .andExpect(status().isForbidden());

        verifyNoInteractions(postmortemService);
    }

    @Test
    void respondersAreRecordedAsTheAuthorByUsername() throws Exception {
        Incident incident = TestIncidents.persisted("Checkout down", Severity.HIGH, Instant.now());
        incident.resolve("responder", null, Instant.now());
        Postmortem saved = Postmortem.writeFor(incident, CONTENT, "responder", Instant.now());
        ReflectionTestUtils.setField(saved, "version", 0L);
        when(postmortemService.save(any(), any(), any(), any())).thenReturn(saved);

        mvc.perform(write(tokens.forRole(Role.RESPONDER), MockMvcRequestBuilders.put(BASE),
                "{\"summary\": \" Checkout down \", \"impact\": \"\"}"))
                .andExpect(status().isOk());

        // Trimmed, and the blank section stored as empty.
        verify(postmortemService).save(INCIDENT, null, CONTENT, "responder");
    }

    /** S3 being down costs attachments, not the API: a clear 503, never a stack trace. */
    @Test
    void storageOutagesAreReportedAsUnavailable() throws Exception {
        when(postmortemService.announceAttachment(any(), any(), any(), eq(1L), any()))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: Connection refused"));

        mvc.perform(write(tokens.forRole(Role.RESPONDER), MockMvcRequestBuilders.post(BASE + "/attachments"),
                        "{\"fileName\": \"a.txt\", \"contentType\": \"text/plain\", \"sizeBytes\": 1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("File storage unavailable"))
                .andExpect(jsonPath("$.detail").value("Attachments are unavailable right now. Try again in a moment."));
    }

    private static RequestBuilder read(String token, String path) {
        return MockMvcRequestBuilders.get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static RequestBuilder write(String token, MockHttpServletRequestBuilder request, String body) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
