package com.clusterpulse.service;

import com.clusterpulse.model.Alert;
import com.clusterpulse.model.AlertRule;
import com.clusterpulse.model.Incident;
import com.clusterpulse.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages the full incident lifecycle: creation from escalated alerts,
 * timeline tracking, resolution, and duration calculation for SLA reporting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentRepository incidentRepository;

    /**
     * Finds an existing open incident for this cluster, or creates a new one.
     * Groups related alerts into a single incident to avoid duplication.
     */
    public Incident getOrCreateIncident(String clusterId, List<Alert> alerts) {
        return incidentRepository
                .findFirstByClusterIdAndStatusOrderByOpenedAtDesc(
                        clusterId, Incident.IncidentStatus.OPEN)
                .map(existing -> {
                    // Attach new alert IDs to the existing incident
                    List<String> newIds = alerts.stream()
                            .map(Alert::getId)
                            .filter(id -> !existing.getAlertIds().contains(id))
                            .toList();
                    existing.getAlertIds().addAll(newIds);

                    // Escalate severity if needed
                    AlertRule.Severity maxSeverity = alerts.stream()
                            .map(Alert::getSeverity)
                            .reduce(existing.getSeverity(), this::higherSeverity);
                    existing.setSeverity(maxSeverity);

                    existing.addTimelineEntry("ALERTS_ADDED", "system",
                            String.format("Attached %d new alert(s)", newIds.size()));

                    return incidentRepository.save(existing);
                })
                .orElseGet(() -> createIncident(clusterId, alerts));
    }

    private Incident createIncident(String clusterId, List<Alert> alerts) {
        AlertRule.Severity maxSeverity = alerts.stream()
                .map(Alert::getSeverity)
                .reduce(AlertRule.Severity.INFO, this::higherSeverity);

        String title = String.format("Cluster health degradation — %d alert(s)",
                alerts.size());

        String description = alerts.stream()
                .map(Alert::getMessage)
                .collect(Collectors.joining("; "));

        Incident incident = Incident.builder()
                .clusterId(clusterId)
                .title(title)
                .description(description)
                .severity(maxSeverity)
                .status(Incident.IncidentStatus.OPEN)
                .alertIds(alerts.stream().map(Alert::getId).collect(Collectors.toList()))
                .openedAt(Instant.now())
                .build();

        incident.addTimelineEntry("INCIDENT_OPENED", "system",
                "Auto-created from " + alerts.size() + " alert(s)");

        Incident saved = incidentRepository.save(incident);
        log.warn("Incident opened: {} for cluster {}", saved.getId(), clusterId);
        return saved;
    }

    public Incident resolveIncident(String incidentId, String resolvedBy, String resolution) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Incident not found: " + incidentId));

        incident.setStatus(Incident.IncidentStatus.RESOLVED);
        incident.setResolvedAt(Instant.now());
        incident.setResolvedBy(resolvedBy);
        incident.setResolution(resolution);
        incident.setDurationSeconds(
                Duration.between(incident.getOpenedAt(), Instant.now()).getSeconds());

        incident.addTimelineEntry("INCIDENT_RESOLVED", resolvedBy, resolution);

        log.info("Incident resolved: {} (duration={}s)", incidentId, incident.getDurationSeconds());
        return incidentRepository.save(incident);
    }

    public void updateIncident(Incident incident) {
        incidentRepository.save(incident);
    }

    public List<Incident> getAllIncidents() {
        return incidentRepository.findAll();
    }

    public Incident getIncident(String incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Incident not found: " + incidentId));
    }

    private AlertRule.Severity higherSeverity(AlertRule.Severity a, AlertRule.Severity b) {
        return a.ordinal() > b.ordinal() ? a : b;
    }
}
