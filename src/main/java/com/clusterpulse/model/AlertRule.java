package com.clusterpulse.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A configurable threshold rule that triggers alerts when health metrics
 * breach defined boundaries. Rules are scoped to individual clusters,
 * allowing different SLAs for production vs. development environments.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "alert_rules")
public class AlertRule {

    @Id
    private String id;

    @Indexed
    private String clusterId;

    private String ruleName;
    private MetricType metricType;
    private Comparator comparator;
    private double threshold;
    private Severity severity;

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private int cooldownMinutes = 5;  // Suppress re-fires within this window

    @CreatedDate
    private Instant createdAt;

    private Instant lastFiredAt;

    public enum MetricType {
        REPLICATION_LAG_MS,
        CONNECTION_UTILIZATION_PCT,
        STORAGE_UTILIZATION_PCT,
        OPLOG_WINDOW_HOURS,
        SLOW_QUERY_COUNT,
        OPS_PER_SECOND,
        VOTING_MEMBERS_DOWN
    }

    public enum Comparator {
        GREATER_THAN,
        LESS_THAN,
        EQUALS
    }

    public enum Severity {
        INFO, WARNING, CRITICAL
    }
}
