package dev.beacon.incident.api;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.beacon.incident.TestIncidents.persisted;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import dev.beacon.incident.config.SecurityConfig;
import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.service.IncidentService;
import dev.beacon.security.Role;
import dev.beacon.security.test.TestTokens;

/**
 * Who may do what, using real RS256 tokens shaped like auth-service's. They are validated by the
 * service's own decoder (JWKS from a stub standing in for auth-service) and mapped to roles by
 * security-common's converter, so nothing in the security chain is mocked.
 */
@WebMvcTest(IncidentController.class)
@Import(SecurityConfig.class)
class IncidentSecurityTest {

    private static final TestTokens tokens = TestTokens.withNewKey();

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
    private IncidentService incidentService;

    @BeforeEach
    void setUp() {
        authService.stubFor(get("/.well-known/jwks.json").willReturn(okJson(tokens.jwksJson())));
        when(incidentService.list(anyInt(), anyInt())).thenReturn(Page.empty());
        when(incidentService.create(any(), any(), any(), any())).thenAnswer(invocation ->
                persisted(invocation.getArgument(0), invocation.getArgument(2), Instant.now()));
    }

    @Test
    void requestsWithoutATokenAreUnauthorized() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/incidents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyRoleCanReadIncidents() throws Exception {
        for (Role role : Role.values()) {
            mvc.perform(list(tokens.forRole(role))).andExpect(status().isOk());
        }
    }

    @Test
    void viewersCannotTriggerIncidents() throws Exception {
        mvc.perform(create(tokens.forRole(Role.VIEWER)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(incidentService);
    }

    /** Real tokens go through security-common's converter, which names the caller by preferred_username. */
    @Test
    void respondersCanTriggerIncidentsAndAreRecordedByUsername() throws Exception {
        mvc.perform(create(tokens.forRole(Role.RESPONDER)))
                .andExpect(status().isCreated());

        verify(incidentService).create("Search latency above SLO", null, Severity.MEDIUM, "responder");
    }

    @Test
    void viewersCannotChangeAnIncident() throws Exception {
        String viewer = tokens.forRole(Role.VIEWER);
        UUID id = UUID.randomUUID();

        mvc.perform(command(viewer, "/api/incidents/" + id + "/acknowledge", "{\"version\": 0}"))
                .andExpect(status().isForbidden());
        mvc.perform(command(viewer, "/api/incidents/" + id + "/resolve", "{\"version\": 0}"))
                .andExpect(status().isForbidden());
        mvc.perform(command(viewer, "/api/incidents/" + id + "/notes", "{\"text\": \"hello\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(incidentService);
    }

    @Test
    void viewersCanReadTheTimeline() throws Exception {
        UUID id = UUID.randomUUID();
        when(incidentService.timeline(id)).thenReturn(List.of());

        mvc.perform(MockMvcRequestBuilders.get("/api/incidents/{id}/timeline", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forRole(Role.VIEWER)))
                .andExpect(status().isOk());
    }

    @Test
    void respondersCanAcknowledgeAndAreRecordedByUsername() throws Exception {
        Incident incident = persisted("Search latency above SLO", Severity.MEDIUM, Instant.now());
        when(incidentService.acknowledge(eq(incident.getId()), eq(0L), any())).thenReturn(incident);

        mvc.perform(command(tokens.forRole(Role.RESPONDER), "/api/incidents/" + incident.getId() + "/acknowledge",
                        "{\"version\": 0}"))
                .andExpect(status().isOk());

        verify(incidentService).acknowledge(incident.getId(), 0L, "responder");
    }

    /** ADMIN is never named on the endpoint; it gets in only through the role hierarchy. */
    @Test
    void adminsInheritResponderPermissions() throws Exception {
        mvc.perform(create(tokens.forRole(Role.ADMIN)))
                .andExpect(status().isCreated());
    }

    @Test
    void tokensWithoutRolesAreForbidden() throws Exception {
        String noRoles = tokens.token(Role.VIEWER, claims -> claims.claim("roles", List.of()));

        mvc.perform(list(noRoles)).andExpect(status().isForbidden());
    }

    @Test
    void tokensFromAnotherIssuerAreRejected() throws Exception {
        String token = tokens.token(Role.ADMIN, claims -> claims.issuer("https://attacker.example"));

        mvc.perform(list(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokensAreRejected() throws Exception {
        Instant anHourAgo = Instant.now().minusSeconds(3600);
        String token = tokens.token(Role.ADMIN, claims -> claims.issuedAt(anHourAgo).expiresAt(anHourAgo.plusSeconds(60)));

        mvc.perform(list(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokensSignedWithAnotherKeyAreRejected() throws Exception {
        mvc.perform(list(TestTokens.withNewKey().forRole(Role.ADMIN)))
                .andExpect(status().isUnauthorized());
    }

    private static RequestBuilder list(String token) {
        return MockMvcRequestBuilders.get("/api/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static RequestBuilder command(String token, String path, String body) {
        return post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static RequestBuilder create(String token) {
        return post("/api/incidents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Search latency above SLO", "severity": "MEDIUM"}
                        """);
    }
}
