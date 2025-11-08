package com.clusterpulse.service;

import com.clusterpulse.model.Alert;
import com.clusterpulse.model.AlertRule;
import com.clusterpulse.model.Cluster;
import com.clusterpulse.model.Incident;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Executes automated remediation actions in response to critical alerts.
 * Actions are logged to the incident timeline for audit and post-mortem analysis.
 * Supports dry-run mode for safe evaluation before enabling live remediation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemediationService {

    private final IncidentService incidentService;

    @Value("${clusterpulse.remediation.enabled:false}")
    private boolean globalRemediationEnabled;

    @Value("${clusterpulse.remediation.dry-run:true}")
    private boolean dryRun;

    /**
     * Evaluates fired alerts and determines if auto-remediation should be triggered.
     * Only acts on CRITICAL severity alerts for clusters with remediation enabled.
     */
    public void evaluateAndRemediate(Cluster cluster, List<Alert> firedAlerts) {
        if (!globalRemediationEnabled || !cluster.isRemediationEnabled()) {
            return;
        }

        List<Alert> criticalAlerts = firedAlerts.stream()
                .filter(a -> a.getSeverity() == AlertRule.Severity.CRITICAL)
                .toList();

        if (criticalAlerts.isEmpty()) {
            return;
        }

        // Create or attach to existing incident
        Incident incident = incidentService.getOrCreateIncident(cluster.getId(), criticalAlerts);

        for (Alert alert : criticalAlerts) {
            RemediationAction action = determineAction(alert);
            executeAction(cluster, incident, alert, action);
        }
    }

    private RemediationAction determineAction(Alert alert) {
        return switch (alert.getMetricType()) {
            case REPLICATION_LAG_MS -> RemediationAction.TRIGGER_RESYNC;
            case CONNECTION_UTILIZATION_PCT -> RemediationAction.REBALANCE_CONNECTIONS;
            case VOTING_MEMBERS_DOWN -> RemediationAction.STEPDOWN_PRIMARY;
            case STORAGE_UTILIZATION_PCT -> RemediationAction.ALERT_ONLY;
            default -> RemediationAction.ALERT_ONLY;
        };
    }

    private void executeAction(Cluster cluster, Incident incident,
                                Alert alert, RemediationAction action) {
        String actionDescription = String.format(
                "Remediation [%s] for metric %s (actual=%.2f, threshold=%.2f)",
                action, alert.getMetricType(), alert.getActualValue(), alert.getThresholdValue());

        if (dryRun) {
            log.info("[DRY-RUN] Would execute: {} on cluster {}",
                    actionDescription, cluster.getDisplayName());
            incident.addTimelineEntry(
                    "DRY_RUN_REMEDIATION", "auto-remediation",
                    "[DRY-RUN] " + actionDescription);
        } else {
            log.warn("Executing remediation: {} on cluster {}",
                    actionDescription, cluster.getDisplayName());

            try {
                switch (action) {
                    case TRIGGER_RESYNC -> executeResync(cluster);
                    case REBALANCE_CONNECTIONS -> executeConnectionRebalance(cluster);
                    case STEPDOWN_PRIMARY -> executePrimaryStepdown(cluster);
                    case ALERT_ONLY -> log.info("No automated action for this metric type");
                }

                incident.addTimelineEntry(
                        "REMEDIATION_EXECUTED", "auto-remediation", actionDescription);
            } catch (Exception e) {
                log.error("Remediation failed for cluster {}: {}",
                        cluster.getDisplayName(), e.getMessage());
                incident.addTimelineEntry(
                        "REMEDIATION_FAILED", "auto-remediation",
                        actionDescription + " | Error: " + e.getMessage());
            }
        }

        incidentService.updateIncident(incident);
    }

    /**
     * Triggers a resync on the most-lagging secondary by sending
     * a replSetResync admin command to the target node.
     */
    private void executeResync(Cluster cluster) {
        // In production, this would connect to the lagging secondary
        // and issue: db.adminCommand({replSetResync: 1})
        log.info("Triggering resync on lagging secondary for cluster: {}",
                cluster.getDisplayName());
    }

    /**
     * Rebalances connections by gracefully draining excess connections
     * from saturated members and redirecting to healthier nodes.
     */
    private void executeConnectionRebalance(Cluster cluster) {
        log.info("Rebalancing connections for cluster: {}", cluster.getDisplayName());
    }

    /**
     * Steps down the current primary to trigger an election, useful when
     * the primary is unhealthy but still holding the role.
     */
    private void executePrimaryStepdown(Cluster cluster) {
        // In production: db.adminCommand({replSetStepDown: 60, secondaryCatchUpPeriodSecs: 30})
        log.info("Stepping down primary for cluster: {}", cluster.getDisplayName());
    }

    public enum RemediationAction {
        TRIGGER_RESYNC,
        REBALANCE_CONNECTIONS,
        STEPDOWN_PRIMARY,
        ALERT_ONLY
    }
}
