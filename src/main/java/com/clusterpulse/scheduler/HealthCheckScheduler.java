package com.clusterpulse.scheduler;

import com.clusterpulse.model.Alert;
import com.clusterpulse.model.Cluster;
import com.clusterpulse.model.HealthSnapshot;
import com.clusterpulse.service.AlertEngineService;
import com.clusterpulse.service.ClusterRegistryService;
import com.clusterpulse.service.HealthPollerService;
import com.clusterpulse.service.RemediationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates the periodic health check pipeline:
 *   1. Poll each registered cluster for health metrics — in parallel
 *   2. Update the cluster's status in the registry
 *   3. Evaluate alert rules against the new snapshot
 *   4. Trigger remediation for critical alerts (if enabled)
 *
 * Clusters are polled concurrently using a dedicated executor so that a slow
 * or unreachable cluster does not delay polling for healthy ones.
 */
@Slf4j
@Component
public class HealthCheckScheduler {

    private final ClusterRegistryService clusterRegistryService;
    private final HealthPollerService healthPollerService;
    private final AlertEngineService alertEngineService;
    private final RemediationService remediationService;
    private final Executor pollExecutor;

    public HealthCheckScheduler(ClusterRegistryService clusterRegistryService,
                                 HealthPollerService healthPollerService,
                                 AlertEngineService alertEngineService,
                                 RemediationService remediationService,
                                 @Qualifier("clusterPollExecutor") Executor pollExecutor) {
        this.clusterRegistryService = clusterRegistryService;
        this.healthPollerService = healthPollerService;
        this.alertEngineService = alertEngineService;
        this.remediationService = remediationService;
        this.pollExecutor = pollExecutor;
    }

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

        log.info("Starting health check cycle for {} cluster(s) in parallel", clusters.size());

        List<CompletableFuture<Void>> futures = clusters.stream()
                .map(cluster -> CompletableFuture.runAsync(
                        () -> processCluster(cluster), pollExecutor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Health check cycle timed out after 60s — some clusters may not have been polled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Health check cycle was interrupted", e);
        } catch (ExecutionException e) {
            log.error("Unexpected error during health check cycle", e.getCause());
        }

        log.info("Health check cycle complete");
    }

    private void processCluster(Cluster cluster) {
        try {
            HealthSnapshot snapshot = healthPollerService.pollCluster(cluster);

            clusterRegistryService.updateClusterHealth(
                    cluster.getId(),
                    snapshot.getComputedStatus(),
                    cluster.getReplicaSetName(),
                    snapshot.getMembers() != null ? snapshot.getMembers().size() : 0,
                    cluster.getPrimaryHost(),
                    cluster.getMongoVersion()
            );

            List<Alert> firedAlerts = alertEngineService.evaluateRules(
                    cluster.getId(), snapshot);

            if (!firedAlerts.isEmpty()) {
                log.warn("Cluster {} fired {} alert(s)",
                        cluster.getDisplayName(), firedAlerts.size());
                remediationService.evaluateAndRemediate(cluster, firedAlerts);
            }
        } catch (Exception e) {
            log.error("Unexpected error processing cluster {}: {}",
                    cluster.getDisplayName(), e.getMessage(), e);
        }
    }
}
