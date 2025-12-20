package com.clusterpulse.service;

import com.clusterpulse.model.Cluster;
import com.clusterpulse.repository.HealthSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Enforces the snapshot retention window by periodically deleting
 * health_snapshots older than the configured retention period.
 *
 * Runs hourly by default. Each cluster is cleaned independently so that
 * a failure on one cluster does not abort cleanup for the others.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotRetentionService {

    private final ClusterRegistryService clusterRegistryService;
    private final HealthSnapshotRepository snapshotRepository;

    @Value("${clusterpulse.health.snapshot-retention-hours:72}")
    private int retentionHours;

    @Scheduled(fixedRateString = "${clusterpulse.health.retention-cleanup-interval-ms:3600000}")
    public void cleanupExpiredSnapshots() {
        List<Cluster> clusters = clusterRegistryService.findAllClusters();

        if (clusters.isEmpty()) {
            return;
        }

        Instant cutoff = Instant.now().minus(retentionHours, ChronoUnit.HOURS);
        log.debug("Running snapshot retention cleanup. cutoff={}, clusters={}", cutoff, clusters.size());

        int successCount = 0;
        for (Cluster cluster : clusters) {
            try {
                snapshotRepository.deleteByClusterIdAndCapturedAtBefore(cluster.getId(), cutoff);
                successCount++;
            } catch (Exception e) {
                log.error("Snapshot cleanup failed for cluster {} ({}): {}",
                        cluster.getDisplayName(), cluster.getId(), e.getMessage());
            }
        }

        log.info("Snapshot retention cleanup complete: {}/{} cluster(s) cleaned (cutoff={})",
                successCount, clusters.size(), cutoff);
    }
}
