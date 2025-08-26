package com.clusterpulse.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * A point-in-time health reading from a monitored MongoDB replica set.
 * Captured by polling rs.status() and serverStatus() on the target cluster.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "health_snapshots")
@CompoundIndex(def = "{'clusterId': 1, 'capturedAt': -1}")
public class HealthSnapshot {

    @Id
    private String id;

    @Indexed
    private String clusterId;

    private Instant capturedAt;

    // Replication health
    private long maxReplicationLagMs;
    private long oplogWindowHours;
    private int votingMembersUp;
    private int totalVotingMembers;

    // Connection metrics
    private int currentConnections;
    private int availableConnections;
    private double connectionUtilizationPct;

    // Storage metrics
    private long dataStorageMb;
    private long indexStorageMb;
    private long freeStorageMb;
    private double storageUtilizationPct;

    // Operation metrics
    private long opsPerSecond;
    private long queryTimeAvgMs;
    private long slowQueryCount;

    // Member-level details
    private List<MemberHealth> members;

    // Overall assessment
    private Cluster.ClusterStatus computedStatus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberHealth {
        private String host;
        private String state;   // PRIMARY, SECONDARY, ARBITER, etc.
        private boolean healthy;
        private long replicationLagMs;
        private long uptimeSeconds;
        private Instant lastHeartbeat;
    }
}
