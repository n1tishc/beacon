package dev.beacon.incident.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.domain.TimelineEntry;
import dev.beacon.incident.service.IncidentService;

/**
 * Each endpoint declares the minimum role; ADMIN and RESPONDER inherit VIEWER through the role hierarchy.
 * The acting user is {@link Authentication#getName()}, which the shared JWT converter sets to the
 * token's {@code preferred_username}.
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @PostMapping
    @PreAuthorize("hasRole('RESPONDER')")
    public ResponseEntity<IncidentResponse> create(@Valid @RequestBody CreateIncidentRequest request,
                                                   Authentication caller) {
        Incident incident = incidentService.create(
                request.title(), request.description(), request.severity(), caller.getName());
        // Relative Location (allowed by RFC 9110): correct behind any number of proxies, no forwarded-header trust needed.
        return ResponseEntity.created(URI.create("/api/incidents/" + incident.getId()))
                .body(IncidentResponse.from(incident));
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public PageResponse<IncidentResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(incidentService.list(page, size), IncidentResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public IncidentResponse get(@PathVariable UUID id) {
        return IncidentResponse.from(incidentService.get(id));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasRole('RESPONDER')")
    public IncidentResponse acknowledge(@PathVariable UUID id, @Valid @RequestBody AcknowledgeRequest request,
                                        Authentication caller) {
        return IncidentResponse.from(incidentService.acknowledge(id, request.version(), caller.getName()));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasRole('RESPONDER')")
    public IncidentResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest request,
                                    Authentication caller) {
        return IncidentResponse.from(
                incidentService.resolve(id, request.version(), caller.getName(), request.note()));
    }

    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasRole('VIEWER')")
    public List<TimelineEntryResponse> timeline(@PathVariable UUID id) {
        return incidentService.timeline(id).stream().map(TimelineEntryResponse::from).toList();
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasRole('RESPONDER')")
    public ResponseEntity<TimelineEntryResponse> addNote(@PathVariable UUID id, @Valid @RequestBody AddNoteRequest request,
                                                         Authentication caller) {
        TimelineEntry entry = incidentService.addNote(id, caller.getName(), request.text());
        return ResponseEntity.status(HttpStatus.CREATED).body(TimelineEntryResponse.from(entry));
    }
}
