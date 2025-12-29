package com.clusterpulse.service;

import com.clusterpulse.exception.ClusterUnreachableException;
import com.clusterpulse.model.Cluster;
import com.clusterpulse.model.HealthSnapshot;
import com.clusterpulse.repository.HealthSnapshotRepository;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Polls health metrics from target MongoDB replica sets by executing
 * admin commands (replSetGetStatus, serverStatus) against each registered cluster.
 * This mirrors how Atlas's cluster management layer monitors deployments.
 *
 * MongoClientSettings objects are cached per connection URI. Since settings are
 * immutable and tied only to the URI (not per-poll state), rebuilding them on
 * every tick is wasteful — especially under parallel polling.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthPollerService {

    private final HealthSnapshotRepository snapshotRepository;

    private final ConcurrentHashMap<String, MongoClientSettings> settingsCache =
            new ConcurrentHashMap<>();

    /**
     * Connects to a target cluster, collects health metrics, and persists a snapshot.
     * Uses short-lived connections with aggressive timeouts to avoid blocking.
     */
    public HealthSnapshot pollCluster(Cluster cluster) {
        log.debug("Polling cluster: {} ({})", cluster.getDisplayName(), cluster.getId());

        try {
            MongoClientSettings settings = getOrBuildSettings(cluster.getConnectionUri());

            try (MongoClient client = MongoClients.create(settings)) {
                MongoDatabase adminDb = client.getDatabase("admin");

                // Collect replica set status
                Document rsStatus = adminDb.runCommand(new Document("replSetGetStatus", 1));

                // Collect server status
                Document serverStatus = adminDb.runCommand(new Document("serverStatus", 1));

                HealthSnapshot snapshot = buildSnapshot(cluster.getId(), rsStatus, serverStatus);
                return snapshotRepository.save(snapshot);
            }

        } catch (Exception e) {
            log.error("Failed to poll cluster {}: {}", cluster.getDisplayName(), e.getMessage());
            // Save an UNREACHABLE snapshot so the alert engine can still fire
            HealthSnapshot failedSnapshot = HealthSnapshot.builder()
                    .clusterId(cluster.getId())
                    .capturedAt(Instant.now())
                    .computedStatus(Cluster.ClusterStatus.UNREACHABLE)
                    .members(List.of())
                    .build();
            return snapshotRepository.save(failedSnapshot);
        }
    }

    private HealthSnapshot buildSnapshot(String clusterId, Document rsStatus, Document serverStatus) {
        List<HealthSnapshot.MemberHealth> members = new ArrayList<>();
        long maxLag = 0;
        int votingUp = 0;
        int totalVoting = 0;
        String primaryOptime = null;

        @SuppressWarnings("unchecked")
        List<Document> memberDocs = rsStatus.getList("members", Document.class, List.of());

        // First pass: find primary optime
        for (Document member : memberDocs) {
            int state = member.getInteger("state", -1);
            if (state == 1) { // PRIMARY
                Document optime = member.get("optime", Document.class);
                if (optime != null) {
                    primaryOptime = optime.get("ts", org.bson.BsonTimestamp.class) != null
                            ? String.valueOf(optime.get("ts", org.bson.BsonTimestamp.class).getTime())
                            : null;
                }
            }
        }

        // Second pass: build member health
        for (Document member : memberDocs) {
            int state = member.getInteger("state", -1);
            String stateStr = member.getString("stateStr");
            String host = member.getString("name");
            boolean healthy = member.getBoolean("health", 0.0) == 1.0 || member.getDouble("health") == 1.0;

            // Calculate replication lag for secondaries
            long lagMs = 0;
            if (state == 2 && primaryOptime != null) { // SECONDARY
                Document optime = member.get("optime", Document.class);
                if (optime != null) {
                    // Simplified lag calculation
                    lagMs = member.containsKey("optimeDate") && rsStatus.containsKey("date")
                            ? Math.max(0, rsStatus.getDate("date").getTime() - member.getDate("optimeDate").getTime())
                            : 0;
                }
                maxLag = Math.max(maxLag, lagMs);
            }

            // Count voting members
            totalVoting++;
            if (healthy && (state == 1 || state == 2)) {
                votingUp++;
            }

            members.add(HealthSnapshot.MemberHealth.builder()
                    .host(host)
                    .state(stateStr)
                    .healthy(healthy)
                    .replicationLagMs(lagMs)
                    .uptimeSeconds(member.getInteger("uptime", 0))
                    .lastHeartbeat(member.containsKey("lastHeartbeat")
                            ? member.getDate("lastHeartbeat").toInstant() : null)
                    .build());
        }

        // Extract connection metrics
        Document connections = serverStatus.get("connections", Document.class);
        int currentConns = connections != null ? connections.getInteger("current", 0) : 0;
        int availConns = connections != null ? connections.getInteger("available", 0) : 0;
        double connUtil = (currentConns + availConns) > 0
                ? (currentConns * 100.0) / (currentConns + availConns) : 0;

        // Extract operation counters
        Document opcounters = serverStatus.get("opcounters", Document.class);
        long totalOps = 0;
        if (opcounters != null) {
            totalOps = opcounters.getLong("insert") + opcounters.getLong("query")
                     + opcounters.getLong("update") + opcounters.getLong("delete");
        }

        // Determine computed status
        Cluster.ClusterStatus computedStatus = computeStatus(maxLag, connUtil, votingUp, totalVoting);

        return HealthSnapshot.builder()
                .clusterId(clusterId)
                .capturedAt(Instant.now())
                .maxReplicationLagMs(maxLag)
                .oplogWindowHours(48) // Would need oplog query to compute precisely
                .votingMembersUp(votingUp)
                .totalVotingMembers(totalVoting)
                .currentConnections(currentConns)
                .availableConnections(availConns)
                .connectionUtilizationPct(connUtil)
                .opsPerSecond(totalOps)
                .members(members)
                .computedStatus(computedStatus)
                .build();
    }

    private MongoClientSettings getOrBuildSettings(String connectionUri) {
        return settingsCache.computeIfAbsent(connectionUri, uri ->
                MongoClientSettings.builder()
                        .applyConnectionString(new ConnectionString(uri))
                        .applyToSocketSettings(b ->
                                b.connectTimeout(5, TimeUnit.SECONDS)
                                 .readTimeout(10, TimeUnit.SECONDS))
                        .applyToClusterSettings(b ->
                                b.serverSelectionTimeout(5, TimeUnit.SECONDS))
                        .build());
    }

    private Cluster.ClusterStatus computeStatus(long maxLagMs, double connUtil,
                                                  int votingUp, int totalVoting) {
        // No quorum — critical
        if (votingUp <= totalVoting / 2) {
            return Cluster.ClusterStatus.CRITICAL;
        }
        // High replication lag or connection saturation
        if (maxLagMs > 30_000 || connUtil > 90) {
            return Cluster.ClusterStatus.CRITICAL;
        }
        if (maxLagMs > 10_000 || connUtil > 75 || votingUp < totalVoting) {
            return Cluster.ClusterStatus.DEGRADED;
        }
        return Cluster.ClusterStatus.HEALTHY;
    }
}
