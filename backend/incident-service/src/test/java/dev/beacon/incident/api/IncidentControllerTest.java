package dev.beacon.incident.api;

import static dev.beacon.incident.TestIncidents.persisted;
import static dev.beacon.incident.TestIncidents.saved;
import static dev.beacon.incident.TestIncidents.withVersion;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.beacon.incident.config.SecurityConfig;
import dev.beacon.incident.domain.IllegalTransitionException;
import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentEvent;
import dev.beacon.incident.domain.IncidentNotFoundException;
import dev.beacon.incident.domain.IncidentStatus;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.domain.StaleIncidentException;
import dev.beacon.incident.service.IncidentService;
import dev.beacon.security.Role;

/**
 * Request/response behaviour of the incident API, including how every error becomes a problem
 * response. Callers are authenticated with Spring Security's {@code jwt()} helper; token validation
 * and role enforcement are covered in IncidentSecurityTest.
 */
@WebMvcTest(IncidentController.class)
@Import(SecurityConfig.class)
class IncidentControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    /** {@code jwt()} names the caller after {@code sub}; with real tokens it is {@code preferred_username}. */
    private static final String CALLER = "riley";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IncidentService incidentService;

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        Incident incident = persisted(Incident.trigger("API 5xx rate above 5%", "Since 09:58", Severity.HIGH, NOW));
        when(incidentService.create("API 5xx rate above 5%", "Since 09:58", Severity.HIGH, CALLER)).thenReturn(incident);

        mvc.perform(json(post("/api/incidents"), """
                        {"title": "API 5xx rate above 5%", "description": "Since 09:58", "severity": "HIGH"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/incidents/" + incident.getId()))
                .andExpect(jsonPath("$.id").value(incident.getId().toString()))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.status").value("TRIGGERED"))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-15T10:00:00Z"))
                .andExpect(jsonPath("$.acknowledgedAt").isEmpty());
    }

    @Test
    void createRejectsBlankTitleWithAFieldError() throws Exception {
        mvc.perform(json(post("/api/incidents"), """
                        {"title": "  ", "severity": "HIGH"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").value("title"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));

        verifyNoInteractions(incidentService);
    }

    @Test
    void createRejectsUnknownSeverity() throws Exception {
        mvc.perform(json(post("/api/incidents"), """
                        {"title": "Something broke", "severity": "APOCALYPTIC"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(incidentService);
    }

    @Test
    void listReturnsAStablePageShape() throws Exception {
        Incident incident = persisted("Queue backlog growing", Severity.MEDIUM, NOW);
        when(incidentService.list(0, 20)).thenReturn(new PageImpl<>(List.of(incident), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/incidents").with(responder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Queue backlog growing"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listRejectsOversizedPagesWithAFieldError() throws Exception {
        mvc.perform(get("/api/incidents").param("size", "500").with(responder()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("size"));

        verifyNoInteractions(incidentService);
    }

    @Test
    void getReturns404ProblemForUnknownIncident() throws Exception {
        UUID id = UUID.randomUUID();
        when(incidentService.get(id)).thenThrow(new IncidentNotFoundException(id));

        mvc.perform(get("/api/incidents/{id}", id).with(responder()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Incident not found"))
                .andExpect(jsonPath("$.detail").value("No incident exists with id " + id));
    }

    @Test
    void acknowledgeReturnsTheUpdatedIncident() throws Exception {
        Incident incident = persisted("Checkout 502s", Severity.HIGH, NOW);
        incident.acknowledge(CALLER, NOW.plusSeconds(60));
        withVersion(incident, 1);
        when(incidentService.acknowledge(incident.getId(), 0, CALLER)).thenReturn(incident);

        mvc.perform(json(post("/api/incidents/{id}/acknowledge", incident.getId()), """
                        {"version": 0}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.acknowledgedAt").value("2026-01-15T10:01:00Z"));
    }

    @Test
    void acknowledgeRequiresTheVersion() throws Exception {
        mvc.perform(json(post("/api/incidents/{id}/acknowledge", UUID.randomUUID()), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be null"));

        verifyNoInteractions(incidentService);
    }

    @Test
    void aStaleVersionIs409IncidentChanged() throws Exception {
        UUID id = UUID.randomUUID();
        when(incidentService.acknowledge(id, 0, CALLER)).thenThrow(new StaleIncidentException(id, 0, 1));

        mvc.perform(json(post("/api/incidents/{id}/acknowledge", id), """
                        {"version": 0}
                        """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value(ApiExceptionHandler.INCIDENT_CHANGED));
    }

    /** The database-level @Version check, for two commands that both passed the version check. */
    @Test
    void aLostRaceIsAlso409IncidentChanged() throws Exception {
        UUID id = UUID.randomUUID();
        when(incidentService.resolve(eq(id), anyLong(), eq(CALLER), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Incident.class, id));

        mvc.perform(json(post("/api/incidents/{id}/resolve", id), """
                        {"version": 1}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value(ApiExceptionHandler.INCIDENT_CHANGED));
    }

    @Test
    void anIllegalTransitionIs409WithItsOwnTitle() throws Exception {
        UUID id = UUID.randomUUID();
        when(incidentService.acknowledge(id, 2, CALLER))
                .thenThrow(new IllegalTransitionException(id, IncidentStatus.RESOLVED, IncidentStatus.ACKNOWLEDGED));

        mvc.perform(json(post("/api/incidents/{id}/acknowledge", id), """
                        {"version": 2}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Transition not allowed"))
                .andExpect(jsonPath("$.detail").value(
                        "Incident " + id + " is RESOLVED and cannot move to ACKNOWLEDGED."));
    }

    @Test
    void resolvePassesTheResolutionNote() throws Exception {
        Incident incident = persisted("Checkout 502s", Severity.HIGH, NOW);
        incident.resolve(CALLER, "Rolled back", NOW.plusSeconds(60));
        when(incidentService.resolve(incident.getId(), 0, CALLER, "Rolled back")).thenReturn(incident);

        mvc.perform(json(post("/api/incidents/{id}/resolve", incident.getId()), """
                        {"version": 0, "note": "Rolled back"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").value("2026-01-15T10:01:00Z"));
    }

    @Test
    void resolveRejectsAnOversizedNote() throws Exception {
        mvc.perform(json(post("/api/incidents/{id}/resolve", UUID.randomUUID()),
                        "{\"version\": 0, \"note\": \"" + "x".repeat(4001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));

        verifyNoInteractions(incidentService);
    }

    @Test
    void addNoteReturns201WithTheEntry() throws Exception {
        UUID id = UUID.randomUUID();
        when(incidentService.addNote(id, CALLER, "Paged the DB team")).thenReturn(
                saved(new IncidentEvent.NoteAdded(id, CALLER, NOW, "Paged the DB team"), 7));

        mvc.perform(json(post("/api/incidents/{id}/notes", id), """
                        {"text": "Paged the DB team"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.type").value("NOTE"))
                .andExpect(jsonPath("$.actor").value(CALLER))
                .andExpect(jsonPath("$.note").value("Paged the DB team"));
    }

    @Test
    void addNoteRejectsBlankText() throws Exception {
        mvc.perform(json(post("/api/incidents/{id}/notes", UUID.randomUUID()), """
                        {"text": ""}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("text"));

        verifyNoInteractions(incidentService);
    }

    @Test
    void timelineListsEntriesInTheOrderTheServiceReturns() throws Exception {
        UUID id = UUID.randomUUID();
        when(incidentService.timeline(id)).thenReturn(List.of(
                saved(new IncidentEvent.Triggered(id, "avery", NOW, Severity.LOW), 1),
                saved(new IncidentEvent.Acknowledged(id, CALLER, NOW.plusSeconds(30)), 2)));

        mvc.perform(get("/api/incidents/{id}/timeline", id).with(responder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("TRIGGERED"))
                .andExpect(jsonPath("$[0].actor").value("avery"))
                .andExpect(jsonPath("$[1].type").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$[1].occurredAt").value("2026-01-15T10:00:30Z"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.with(responder()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static JwtRequestPostProcessor responder() {
        return jwt().jwt(token -> token.subject(CALLER))
                .authorities(new SimpleGrantedAuthority(Role.RESPONDER.authority()));
    }
}
