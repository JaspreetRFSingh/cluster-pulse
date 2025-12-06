package com.clusterpulse.controller;

import com.clusterpulse.dto.IncidentResponse;
import com.clusterpulse.model.Incident;
import com.clusterpulse.service.IncidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
@Tag(name = "Incidents", description = "Incident lifecycle management")
public class IncidentController {

    private final IncidentService incidentService;

    @GetMapping
    @Operation(summary = "List all incidents across all clusters")
    public ResponseEntity<List<IncidentResponse>> getAllIncidents() {
        List<IncidentResponse> incidents = incidentService.getAllIncidents().stream()
                .map(IncidentResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(incidents);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get incident details including full timeline")
    public ResponseEntity<IncidentResponse> getIncident(@PathVariable String id) {
        Incident incident = incidentService.getIncident(id);
        return ResponseEntity.ok(IncidentResponse.from(incident));
    }

    @PatchMapping("/{id}/resolve")
    @Operation(summary = "Resolve an incident with a resolution note")
    public ResponseEntity<IncidentResponse> resolveIncident(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String resolvedBy = body.getOrDefault("resolvedBy", "operator");
        String resolution = body.getOrDefault("resolution", "Resolved manually");

        Incident incident = incidentService.resolveIncident(id, resolvedBy, resolution);
        return ResponseEntity.ok(IncidentResponse.from(incident));
    }
}
