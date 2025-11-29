package com.clusterpulse.controller;

import com.clusterpulse.dto.AlertRuleRequest;
import com.clusterpulse.model.Alert;
import com.clusterpulse.model.AlertRule;
import com.clusterpulse.service.AlertEngineService;
import com.clusterpulse.service.ClusterRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Alerts", description = "Alert rule configuration and alert management")
public class AlertController {

    private final AlertEngineService alertEngineService;
    private final ClusterRegistryService clusterRegistryService;

    @PostMapping("/clusters/{clusterId}/rules")
    @Operation(summary = "Create an alert rule for a specific cluster")
    public ResponseEntity<AlertRule> createRule(
            @PathVariable String clusterId,
            @Valid @RequestBody AlertRuleRequest request) {

        // Validate cluster exists
        clusterRegistryService.findClusterOrThrow(clusterId);

        AlertRule rule = AlertRule.builder()
                .ruleName(request.getRuleName())
                .metricType(request.getMetricType())
                .comparator(request.getComparator())
                .threshold(request.getThreshold())
                .severity(request.getSeverity())
                .cooldownMinutes(request.getCooldownMinutes())
                .enabled(true)
                .build();

        AlertRule saved = alertEngineService.createRule(clusterId, rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/clusters/{clusterId}/rules")
    @Operation(summary = "List all alert rules for a cluster")
    public ResponseEntity<List<AlertRule>> getRulesForCluster(
            @PathVariable String clusterId) {
        clusterRegistryService.findClusterOrThrow(clusterId);
        return ResponseEntity.ok(alertEngineService.getRulesForCluster(clusterId));
    }

    @GetMapping("/alerts")
    @Operation(summary = "List all fired alerts across all clusters")
    public ResponseEntity<List<Alert>> getAllAlerts() {
        return ResponseEntity.ok(alertEngineService.getAllAlerts());
    }

    @PatchMapping("/alerts/{id}/acknowledge")
    @Operation(summary = "Acknowledge an alert")
    public ResponseEntity<Alert> acknowledgeAlert(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String acknowledgedBy = body.getOrDefault("acknowledgedBy", "operator");
        Alert alert = alertEngineService.acknowledgeAlert(id, acknowledgedBy);
        return ResponseEntity.ok(alert);
    }
}
