package com.clusterpulse.dto;

import com.clusterpulse.model.Cluster;
import com.clusterpulse.model.HealthSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterStatusResponse {

    private String id;
    private String displayName;
    private String environment;
    private Cluster.ClusterStatus status;
    private String replicaSetName;
    private int memberCount;
    private String primaryHost;
    private String mongoVersion;
    private boolean remediationEnabled;
    private Map<String, String> tags;
    private Instant registeredAt;
    private Instant lastHealthCheckAt;

    // Latest health summary (nullable if no snapshot yet)
    private HealthSummary latestHealth;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HealthSummary {
        private long maxReplicationLagMs;
        private double connectionUtilizationPct;
        private double storageUtilizationPct;
        private long oplogWindowHours;
        private long opsPerSecond;
        private int votingMembersUp;
        private int totalVotingMembers;
    }

    public static ClusterStatusResponse from(Cluster cluster, HealthSnapshot snapshot) {
        ClusterStatusResponseBuilder builder = ClusterStatusResponse.builder()
                .id(cluster.getId())
                .displayName(cluster.getDisplayName())
                .environment(cluster.getEnvironment())
                .status(cluster.getStatus())
                .replicaSetName(cluster.getReplicaSetName())
                .memberCount(cluster.getMemberCount())
                .primaryHost(cluster.getPrimaryHost())
                .mongoVersion(cluster.getMongoVersion())
                .remediationEnabled(cluster.isRemediationEnabled())
                .tags(cluster.getTags())
                .registeredAt(cluster.getRegisteredAt())
                .lastHealthCheckAt(cluster.getLastHealthCheckAt());

        if (snapshot != null) {
            builder.latestHealth(HealthSummary.builder()
                    .maxReplicationLagMs(snapshot.getMaxReplicationLagMs())
                    .connectionUtilizationPct(snapshot.getConnectionUtilizationPct())
                    .storageUtilizationPct(snapshot.getStorageUtilizationPct())
                    .oplogWindowHours(snapshot.getOplogWindowHours())
                    .opsPerSecond(snapshot.getOpsPerSecond())
                    .votingMembersUp(snapshot.getVotingMembersUp())
                    .totalVotingMembers(snapshot.getTotalVotingMembers())
                    .build());
        }

        return builder.build();
    }
}
