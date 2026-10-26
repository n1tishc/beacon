package dev.beacon.notification.config;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import dev.beacon.notification.NotificationProperties;
import dev.beacon.notification.TestEvents;
import dev.beacon.notification.api.InternalNotificationController;
import dev.beacon.notification.api.NotificationController;
import dev.beacon.notification.service.NotificationService;
import dev.beacon.security.Role;
import dev.beacon.security.test.TestTokens;

/** Each caller gets into its own API only: the service token opens /internal, user tokens open /api. */
@WebMvcTest(controllers = {InternalNotificationController.class, NotificationController.class},
        properties = {"beacon.notifications.service-token=right-token", "beacon.notifications.public-url=http://beacon"})
@Import(SecurityConfig.class)
@EnableConfigurationProperties(NotificationProperties.class)
class ServiceTokenSecurityTest {

    private static final TestTokens tokens = TestTokens.withNewKey();

    @RegisterExtension
    static WireMockExtension authService = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void jwksFromStub(DynamicPropertyRegistry registry) {
        registry.add("AUTH_JWKS_URI", () -> authService.baseUrl() + "/.well-known/jwks.json");
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        authService.stubFor(get("/.well-known/jwks.json").willReturn(okJson(tokens.jwksJson())));
        when(notificationService.notify(any())).thenReturn(List.of());
    }

    @Test
    void theServiceTokenOpensTheInternalApi() throws Exception {
        mvc.perform(internal().header(ServiceTokenFilter.HEADER, "right-token")).andExpect(status().isOk());
    }

    @Test
    void theInternalApiRejectsAMissingOrWrongToken() throws Exception {
        mvc.perform(internal()).andExpect(status().isUnauthorized());
        mvc.perform(internal().header(ServiceTokenFilter.HEADER, "wrong-token")).andExpect(status().isUnauthorized());
        mvc.perform(internal().header(ServiceTokenFilter.HEADER, "right-token-and-more")).andExpect(status().isUnauthorized());

        verifyNoInteractions(notificationService);
    }

    @Test
    void aUserTokenDoesNotOpenTheInternalApi() throws Exception {
        mvc.perform(internal().header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forRole(Role.ADMIN)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(notificationService);
    }

    @Test
    void theServiceTokenDoesNotOpenTheUserApi() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/notifications/channels").header(ServiceTokenFilter.HEADER, "right-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyRoleCanReadNotifications() throws Exception {
        for (Role role : Role.values()) {
            mvc.perform(MockMvcRequestBuilders.get("/api/notifications").param("incidentId", UUID.randomUUID().toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forRole(role)))
                    .andExpect(status().isOk());
        }
    }

    private static MockHttpServletRequestBuilder internal() {
        return MockMvcRequestBuilders.post("/internal/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(TestEvents.triggered());
    }
}
