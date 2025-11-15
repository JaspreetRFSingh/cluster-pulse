package com.clusterpulse.scheduler;

import com.clusterpulse.model.Alert;
import com.clusterpulse.model.Cluster;
import com.clusterpulse.model.HealthSnapshot;
import com.clusterpulse.service.AlertEngineService;
import com.clusterpulse.service.ClusterRegistryService;
import com.clusterpulse.service.HealthPollerService;
import com.clusterpulse.service.RemediationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Orchestrates the periodic health check pipeline:
 *   1. Poll each registered cluster for health metrics
 *   2. Update the cluster's status in the registry
 *   3. Evaluate alert rules against the new snapshot
 *   4. Trigger remediation for critical alerts (if enabled)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthCheckScheduler {

    private final ClusterRegistryService clusterRegistryService;
    private final HealthPollerService healthPollerService;
    private final AlertEngineService alertEngineService;
    private final RemediationService remediationService;

    @Scheduled(
            fixedDelayString = "${clusterpulse.polling.interval-seconds:30}000",
            initialDelayString = "${clusterpulse.polling.initial-delay-seconds:10}000"
    )
    public void runHealthCheckCycle() {
        List<Cluster> clusters = clusterRegistryService.findAllClusters();

        if (clusters.isEmpty()) {
            log.debug("No clusters registered, skipping health check cycle");
            return;
        }

        log.info("Starting health check cycle for {} cluster(s)", clusters.size());

        for (Cluster cluster : clusters) {
            try {
                processCluster(cluster);
            } catch (Exception e) {
                log.error("Unexpected error processing cluster {}: {}",
                        cluster.getDisplayName(), e.getMessage(), e);
            }
        }

        log.info("Health check cycle complete");
    }

    private void processCluster(Cluster cluster) {
        // Step 1: Poll health metrics
        HealthSnapshot snapshot = healthPollerService.pollCluster(cluster);

        // Step 2: Update cluster status
        clusterRegistryService.updateClusterHealth(
                cluster.getId(),
                snapshot.getComputedStatus(),
                cluster.getReplicaSetName(),
                snapshot.getMembers() != null ? snapshot.getMembers().size() : 0,
                cluster.getPrimaryHost(),
                cluster.getMongoVersion()
        );

        // Step 3: Evaluate alert rules
        List<Alert> firedAlerts = alertEngineService.evaluateRules(
                cluster.getId(), snapshot);

        if (!firedAlerts.isEmpty()) {
            log.warn("Cluster {} fired {} alert(s)",
                    cluster.getDisplayName(), firedAlerts.size());

            // Step 4: Auto-remediation (if enabled)
            remediationService.evaluateAndRemediate(cluster, firedAlerts);
        }
    }
}
