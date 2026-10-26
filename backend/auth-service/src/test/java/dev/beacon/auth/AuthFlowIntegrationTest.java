package dev.beacon.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.beacon.auth.api.RefreshTokenCookies;

/** The whole auth flow over HTTP, against a real Postgres. */
@SpringBootTest(properties = {
        "beacon.auth.demo-users.enabled=true",
        "beacon.auth.demo-users.password=" + AuthFlowIntegrationTest.DEMO_PASSWORD
})
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class AuthFlowIntegrationTest {

    static final String DEMO_PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void loginReturnsAnAccessTokenAndSetsAHardenedRefreshCookie() throws Exception {
        mvc.perform(login("Responder", DEMO_PASSWORD)) // usernames are case-insensitive
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.username").value("responder"))
                .andExpect(jsonPath("$.user.role").value("RESPONDER"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().httpOnly(RefreshTokenCookies.NAME, true))
                .andExpect(cookie().secure(RefreshTokenCookies.NAME, true))
                .andExpect(cookie().sameSite(RefreshTokenCookies.NAME, "Strict"))
                .andExpect(cookie().path(RefreshTokenCookies.NAME, "/api/auth"));
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {
        String wrongPassword = mvc.perform(login("responder", "not-the-password"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownUser = mvc.perform(login("nobody", "not-the-password"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownUser).isEqualTo(wrongPassword).contains("Invalid username or password.");
    }

    @Test
    void refreshRotatesTheTokenAndReplayingAnOldOneRevokesEverySession() throws Exception {
        Cookie original = loginAs("responder").refreshCookie();

        Cookie rotated = mvc.perform(post("/api/auth/refresh").cookie(original))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getCookie(RefreshTokenCookies.NAME);
        assertThat(rotated.getValue()).isNotEqualTo(original.getValue());

        // An attacker replays the stolen, already-rotated token...
        mvc.perform(post("/api/auth/refresh").cookie(original))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge(RefreshTokenCookies.NAME, 0));
        // ...which also kills the legitimate user's current session.
        mvc.perform(post("/api/auth/refresh").cookie(rotated))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithoutACookieIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Session expired"));
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        Cookie refresh = loginAs("viewer").refreshCookie();

        mvc.perform(post("/api/auth/logout").cookie(refresh))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(RefreshTokenCookies.NAME, 0))
                .andExpect(cookie().path(RefreshTokenCookies.NAME, "/api/auth"));

        mvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsTheUserBehindTheAccessToken() throws Exception {
        String token = loginAs("viewer").accessToken();

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("viewer"))
                .andExpect(jsonPath("$.role").value("VIEWER"));

        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsCanManageUsers() throws Exception {
        String viewer = loginAs("viewer").accessToken();
        String responder = loginAs("responder").accessToken();
        String admin = loginAs("admin").accessToken();
        String newUser = """
                {"username": "Casey.Oncall", "displayName": "Casey On-call", "password": "a-long-enough-password", "role": "RESPONDER"}
                """;

        mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer))
                .andExpect(status().isForbidden());
        mvc.perform(createUser(responder, newUser))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItems("admin", "responder", "viewer")));
        mvc.perform(createUser(admin, newUser))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("casey.oncall"));
        mvc.perform(createUser(admin, newUser))
                .andExpect(status().isConflict());
        mvc.perform(createUser(admin, """
                        {"username": "short.pw", "displayName": "Short", "password": "tooshort", "role": "VIEWER"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jwksPublishesOnlyThePublicKey() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist());
    }

    private record LoginResult(String accessToken, Cookie refreshCookie) {
    }

    private LoginResult loginAs(String username) throws Exception {
        MvcResult result = mvc.perform(login(username, DEMO_PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return new LoginResult(body.get("accessToken").asText(), result.getResponse().getCookie(RefreshTokenCookies.NAME));
    }

    private static org.springframework.test.web.servlet.RequestBuilder login(String username, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "%s", "password": "%s"}
                        """.formatted(username, password));
    }

    private static org.springframework.test.web.servlet.RequestBuilder createUser(String token, String body) {
        return post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
