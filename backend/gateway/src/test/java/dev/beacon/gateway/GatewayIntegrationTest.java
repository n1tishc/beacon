package dev.beacon.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import dev.beacon.security.Role;
import dev.beacon.security.test.TestTokens;

/**
 * Runs the real gateway against two WireMock servers standing in for auth-service and
 * incident-service. Tokens are real RS256 JWTs validated against a JWKS served by the fake
 * auth-service, so these tests exercise the actual decoder, issuer check and routes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "beacon.gateway.rate-limit.routes.auth-login.capacity=3",
                "beacon.gateway.rate-limit.routes.auth-login.period=1h"
        })
class GatewayIntegrationTest {

    private static final TestTokens tokens = TestTokens.withNewKey();

    @RegisterExtension
    static WireMockExtension authService = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @RegisterExtension
    static WireMockExtension incidentService = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @RegisterExtension
    static WireMockExtension notificationService = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void pointRoutesAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("AUTH_SERVICE_URL", authService::baseUrl);
        registry.add("INCIDENT_SERVICE_URL", incidentService::baseUrl);
        registry.add("NOTIFICATION_SERVICE_URL", notificationService::baseUrl);
        registry.add("AUTH_JWKS_URI", () -> authService.baseUrl() + "/.well-known/jwks.json");
    }

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void stubBackends() {
        authService.stubFor(get("/.well-known/jwks.json").willReturn(okJson(tokens.jwksJson())));
        authService.stubFor(WireMock.any(urlPathMatching("/api/.*")).willReturn(okJson("{\"service\":\"auth\"}")));
        incidentService.stubFor(WireMock.any(urlPathMatching("/api/.*")).willReturn(okJson("{\"service\":\"incident\"}")));
        notificationService.stubFor(WireMock.any(anyUrl()).willReturn(okJson("{\"service\":\"notification\"}")));
    }

    @Test
    void rejectsRequestsWithoutATokenBeforeTheyReachAService() {
        client.get().uri("/api/incidents").exchange()
                .expectStatus().isUnauthorized();

        incidentService.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void forwardsAuthenticatedRequestsWithTheirBearerToken() {
        String token = tokens.forRole(Role.VIEWER);

        client.get().uri("/api/incidents?page=0").headers(h -> h.setBearerAuth(token)).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("incident");

        incidentService.verify(getRequestedFor(urlEqualTo("/api/incidents?page=0"))
                .withHeader("Authorization", equalTo("Bearer " + token)));
    }

    @Test
    void routesUserManagementToAuthService() {
        client.get().uri("/api/users").headers(h -> h.setBearerAuth(tokens.forRole(Role.ADMIN))).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("auth");
    }

    @Test
    void routesNotificationViewsToNotificationService() {
        client.get().uri("/api/notifications/channels").headers(h -> h.setBearerAuth(tokens.forRole(Role.VIEWER))).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("notification");
    }

    /** Only incident-service may call it, from inside the network; the gateway must not expose it. */
    @Test
    void neverExposesNotificationServicesInternalApi() {
        client.post().uri("/internal/notifications").headers(h -> h.setBearerAuth(tokens.forRole(Role.ADMIN))).exchange()
                .expectStatus().isNotFound();

        notificationService.verify(0, anyRequestedFor(urlPathMatching("/internal/.*")));
    }

    @Test
    void rejectsTokensFromAnotherIssuer() {
        String token = tokens.token(Role.ADMIN, claims -> claims.issuer("https://attacker.example"));

        client.get().uri("/api/incidents").headers(h -> h.setBearerAuth(token)).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsExpiredTokens() {
        Instant anHourAgo = Instant.now().minusSeconds(3600);
        String token = tokens.token(Role.ADMIN, claims -> claims.issuedAt(anHourAgo).expiresAt(anHourAgo.plusSeconds(60)));

        client.get().uri("/api/incidents").headers(h -> h.setBearerAuth(token)).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokensSignedWithAnUnknownKey() {
        String forged = TestTokens.withNewKey().forRole(Role.ADMIN);

        client.get().uri("/api/incidents").headers(h -> h.setBearerAuth(forged)).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void loginIsReachableWithoutAToken() {
        client.post().uri("/api/auth/login").header("X-Forwarded-For", "203.0.113.10").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("auth");
    }

    @Test
    void rateLimitsLoginPerClientIp() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            login("198.51.100.7").expectStatus().isOk();
        }

        login("198.51.100.7").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        login("198.51.100.8").expectStatus().isOk();
    }

    @Test
    void healthIsPublic() {
        client.get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec login(String clientIp) {
        return client.post().uri("/api/auth/login").header("X-Forwarded-For", clientIp).exchange();
    }
}
