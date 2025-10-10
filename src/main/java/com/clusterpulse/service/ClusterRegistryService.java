package com.clusterpulse.service;

import com.clusterpulse.dto.ClusterRegistrationRequest;
import com.clusterpulse.dto.ClusterStatusResponse;
import com.clusterpulse.exception.ClusterNotFoundException;
import com.clusterpulse.model.Cluster;
import com.clusterpulse.model.HealthSnapshot;
import com.clusterpulse.repository.ClusterRepository;
import com.clusterpulse.repository.HealthSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterRegistryService {

    private final ClusterRepository clusterRepository;
    private final HealthSnapshotRepository healthSnapshotRepository;

    public Cluster registerCluster(ClusterRegistrationRequest request) {
        if (clusterRepository.existsByConnectionUri(request.getConnectionUri())) {
            throw new IllegalArgumentException("A cluster with this connection URI is already registered");
        }

        Cluster cluster = Cluster.builder()
                .displayName(request.getDisplayName())
                .connectionUri(request.getConnectionUri())
                .environment(request.getEnvironment())
                .remediationEnabled(request.isRemediationEnabled())
                .tags(request.getTags() != null ? request.getTags() : java.util.Map.of())
                .status(Cluster.ClusterStatus.UNKNOWN)
                .build();

        Cluster saved = clusterRepository.save(cluster);
        log.info("Registered new cluster: {} ({})", saved.getDisplayName(), saved.getId());
        return saved;
    }

    public List<ClusterStatusResponse> listAllClusters() {
        return clusterRepository.findAll().stream()
                .map(cluster -> {
                    HealthSnapshot latest = healthSnapshotRepository
                            .findFirstByClusterIdOrderByCapturedAtDesc(cluster.getId())
                            .orElse(null);
                    return ClusterStatusResponse.from(cluster, latest);
                })
                .collect(Collectors.toList());
    }

    public ClusterStatusResponse getClusterStatus(String clusterId) {
        Cluster cluster = findClusterOrThrow(clusterId);
        HealthSnapshot latest = healthSnapshotRepository
                .findFirstByClusterIdOrderByCapturedAtDesc(clusterId)
                .orElse(null);
        return ClusterStatusResponse.from(cluster, latest);
    }

    public void deregisterCluster(String clusterId) {
        Cluster cluster = findClusterOrThrow(clusterId);
        clusterRepository.delete(cluster);
        log.info("Deregistered cluster: {} ({})", cluster.getDisplayName(), clusterId);
    }

    public Cluster updateClusterHealth(String clusterId, Cluster.ClusterStatus status,
                                        String replicaSetName, int memberCount,
                                        String primaryHost, String mongoVersion) {
        Cluster cluster = findClusterOrThrow(clusterId);
        cluster.setStatus(status);
        cluster.setReplicaSetName(replicaSetName);
        cluster.setMemberCount(memberCount);
        cluster.setPrimaryHost(primaryHost);
        cluster.setMongoVersion(mongoVersion);
        cluster.setLastHealthCheckAt(java.time.Instant.now());
        return clusterRepository.save(cluster);
    }

    public Cluster findClusterOrThrow(String clusterId) {
        return clusterRepository.findById(clusterId)
                .orElseThrow(() -> new ClusterNotFoundException("Cluster not found: " + clusterId));
    }

    public List<Cluster> findAllClusters() {
        return clusterRepository.findAll();
    }
}
