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

/**
 * A fired alert — the result of a health snapshot breaching an AlertRule threshold.
 * Alerts are ephemeral signals; they may escalate into Incidents.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "alerts")
@CompoundIndex(def = "{'clusterId': 1, 'firedAt': -1}")
public class Alert {

    @Id
    private String id;

    @Indexed
    private String clusterId;

    private String ruleId;
    private String ruleName;
    private AlertRule.Severity severity;
    private AlertRule.MetricType metricType;

    private double actualValue;
    private double thresholdValue;
    private String message;

    @Builder.Default
    private AlertStatus status = AlertStatus.OPEN;

    private Instant firedAt;
    private Instant acknowledgedAt;
    private String acknowledgedBy;

    private String incidentId;  // Linked incident, if escalated

    public enum AlertStatus {
        OPEN, ACKNOWLEDGED, RESOLVED, ESCALATED
    }
}
