package dev.beacon.incident.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import dev.beacon.incident.domain.Incident;
import dev.beacon.incident.service.IncidentService;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @PostMapping
    public ResponseEntity<IncidentResponse> create(@Valid @RequestBody CreateIncidentRequest request,
                                                   UriComponentsBuilder uriBuilder) {
        Incident incident = incidentService.create(request.title(), request.description(), request.severity());
        URI location = uriBuilder.path("/api/incidents/{id}").buildAndExpand(incident.getId()).toUri();
        return ResponseEntity.created(location).body(IncidentResponse.from(incident));
    }

    @GetMapping
    public PageResponse<IncidentResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(incidentService.list(page, size), IncidentResponse::from);
    }

    @GetMapping("/{id}")
    public IncidentResponse get(@PathVariable UUID id) {
        return IncidentResponse.from(incidentService.get(id));
    }
}
