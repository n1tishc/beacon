package dev.beacon.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import dev.beacon.security.Role;

/**
 * The whole lifecycle over HTTP against a real Postgres: Flyway migrations, the JPA mappings, the
 * {@code @Version} column and the timeline ordering all run for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class IncidentLifecycleIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void triggerAcknowledgeNoteResolve() throws Exception {
        String id = trigger("Checkout returning 502s");

        perform(post("/api/incidents/{id}/acknowledge", id), "riley", "{\"version\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.acknowledgedAt").isNotEmpty());

        perform(post("/api/incidents/{id}/notes", id), "avery", "{\"text\": \"Rolling back 4.2.0\"}")
                .andExpect(status().isCreated());

        // A note is not a change to the incident, so the version is still 1.
        perform(post("/api/incidents/{id}/resolve", id), "riley", "{\"version\": 1, \"note\": \"Rolled back\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/incidents/{id}", id).with(caller("val", Role.VIEWER)))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        String timeline = mvc.perform(get("/api/incidents/{id}/timeline", id).with(caller("val", Role.VIEWER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> types = JsonPath.read(timeline, "$[*].type");
        List<String> actors = JsonPath.read(timeline, "$[*].actor");
        List<String> notes = JsonPath.read(timeline, "$[*].note");
        assertThat(types).containsExactly("TRIGGERED", "ACKNOWLEDGED", "NOTE", "RESOLVED");
        assertThat(actors).containsExactly("riley", "riley", "avery", "riley");
        assertThat(notes).containsExactly("Severity HIGH", null, "Rolling back 4.2.0", "Rolled back");
    }

    @Test
    void resolvingStraightFromTriggeredIsAllowed() throws Exception {
        String id = trigger("Disk alert, false positive");

        perform(post("/api/incidents/{id}/resolve", id), "riley", "{\"version\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.acknowledgedAt").isEmpty());
    }

    @Test
    void aCommandBasedOnAnOldVersionIsRejectedAndChangesNothing() throws Exception {
        String id = trigger("Queue backlog");
        perform(post("/api/incidents/{id}/acknowledge", id), "riley", "{\"version\": 0}")
                .andExpect(status().isOk());

        // Avery's screen still shows version 0 (TRIGGERED) when they click Resolve.
        perform(post("/api/incidents/{id}/resolve", id), "avery", "{\"version\": 0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Incident changed"));

        mvc.perform(get("/api/incidents/{id}", id).with(caller("val", Role.VIEWER)))
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/incidents/{id}/timeline", id).with(caller("val", Role.VIEWER)))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void aResolvedIncidentCannotBeAcknowledged() throws Exception {
        String id = trigger("Cache stampede");
        perform(post("/api/incidents/{id}/resolve", id), "riley", "{\"version\": 0}")
                .andExpect(status().isOk());

        perform(post("/api/incidents/{id}/acknowledge", id), "riley", "{\"version\": 1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Transition not allowed"));
    }

    @Test
    void theTimelineOfAnUnknownIncidentIs404() throws Exception {
        mvc.perform(get("/api/incidents/{id}/timeline", UUID.randomUUID()).with(caller("val", Role.VIEWER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Incident not found"));
    }

    private String trigger(String title) throws Exception {
        String body = perform(post("/api/incidents"), "riley",
                        "{\"title\": \"" + title + "\", \"severity\": \"HIGH\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
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
