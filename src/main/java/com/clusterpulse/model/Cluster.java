package com.clusterpulse.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a registered MongoDB replica set being monitored.
 * Each cluster has a connection URI, health status, and configuration metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "clusters")
public class Cluster {

    @Id
    private String id;

    private String displayName;
    private String connectionUri;
    private String environment;  // PRODUCTION, STAGING, DEVELOPMENT

    @Builder.Default
    private ClusterStatus status = ClusterStatus.UNKNOWN;

    private String replicaSetName;
    private int memberCount;
    private String primaryHost;
    private String mongoVersion;

    @Builder.Default
    private boolean remediationEnabled = false;

    @Builder.Default
    private Map<String, String> tags = Map.of();

    @CreatedDate
    private Instant registeredAt;

    @LastModifiedDate
    private Instant lastUpdatedAt;

    private Instant lastHealthCheckAt;

    public enum ClusterStatus {
        HEALTHY, DEGRADED, CRITICAL, UNREACHABLE, UNKNOWN
    }
}
