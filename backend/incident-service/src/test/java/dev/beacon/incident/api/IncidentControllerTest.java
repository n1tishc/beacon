package dev.beacon.incident.api;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import dev.beacon.incident.config.SecurityConfig;
import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.IncidentNotFoundException;
import dev.beacon.incident.domain.Severity;
import dev.beacon.incident.service.IncidentService;
import dev.beacon.security.Role;

/**
 * Request/response behaviour of the incident API. Callers are authenticated with Spring Security's
 * {@code jwt()} helper; token validation and role enforcement are covered in IncidentSecurityTest.
 */
@WebMvcTest(IncidentController.class)
@Import(SecurityConfig.class)
class IncidentControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IncidentService incidentService;

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        Incident incident = persisted(Incident.trigger("API 5xx rate above 5%", "Since 09:58", Severity.HIGH, NOW));
        when(incidentService.create("API 5xx rate above 5%", "Since 09:58", Severity.HIGH)).thenReturn(incident);

        mvc.perform(post("/api/incidents").with(responder())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "API 5xx rate above 5%", "description": "Since 09:58", "severity": "HIGH"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/incidents/" + incident.getId()))
                .andExpect(jsonPath("$.id").value(incident.getId().toString()))
                .andExpect(jsonPath("$.status").value("TRIGGERED"))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-15T10:00:00Z"));
    }

    @Test
    void createRejectsBlankTitleAsProblemDetail() throws Exception {
        mvc.perform(post("/api/incidents").with(responder())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "  ", "severity": "HIGH"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(incidentService);
    }

    @Test
    void createRejectsUnknownSeverity() throws Exception {
        mvc.perform(post("/api/incidents").with(responder())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Something broke", "severity": "APOCALYPTIC"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(incidentService);
    }

    @Test
    void listReturnsAStablePageShape() throws Exception {
        Incident incident = persisted(Incident.trigger("Queue backlog growing", null, Severity.MEDIUM, NOW));
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
    void listRejectsOversizedPages() throws Exception {
        mvc.perform(get("/api/incidents").param("size", "500").with(responder()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(incidentService);
    }

    @Test
    void getReturns404ProblemForUnknownIncident() throws Exception {
        UUID id = UUID.randomUUID();
        when(incidentService.get(id)).thenThrow(new IncidentNotFoundException(id));

        mvc.perform(get("/api/incidents/{id}", id).with(responder()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Incident not found"));
    }

    private static JwtRequestPostProcessor responder() {
        return jwt().authorities(new SimpleGrantedAuthority(Role.RESPONDER.authority()));
    }

    /** Simulates what JPA does on persist: assigns the generated id. */
    private static Incident persisted(Incident incident) {
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        return incident;
    }
}
