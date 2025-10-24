package com.clusterpulse.service;

import com.clusterpulse.model.Alert;
import com.clusterpulse.model.AlertRule;
import com.clusterpulse.model.HealthSnapshot;
import com.clusterpulse.repository.AlertRepository;
import com.clusterpulse.repository.AlertRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates configured alert rules against the latest health snapshot
 * for each cluster. Supports cooldown windows to prevent alert storms.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEngineService {

    private final AlertRuleRepository ruleRepository;
    private final AlertRepository alertRepository;

    public List<Alert> evaluateRules(String clusterId, HealthSnapshot snapshot) {
        List<AlertRule> rules = ruleRepository.findByClusterIdAndEnabled(clusterId, true);
        List<Alert> firedAlerts = new ArrayList<>();

        for (AlertRule rule : rules) {
            if (isInCooldown(rule)) {
                log.debug("Rule '{}' is in cooldown, skipping", rule.getRuleName());
                continue;
            }

            double actualValue = extractMetricValue(snapshot, rule.getMetricType());
            boolean breached = evaluateThreshold(actualValue, rule.getComparator(), rule.getThreshold());

            if (breached) {
                Alert alert = fireAlert(clusterId, rule, actualValue);
                firedAlerts.add(alert);
                log.warn("Alert fired: {} | cluster={} | actual={} {} threshold={}",
                        rule.getRuleName(), clusterId, actualValue,
                        rule.getComparator(), rule.getThreshold());
            }
        }

        return firedAlerts;
    }

    public double extractMetricValue(HealthSnapshot snapshot, AlertRule.MetricType metricType) {
        if (snapshot == null) return -1;

        return switch (metricType) {
            case REPLICATION_LAG_MS -> snapshot.getMaxReplicationLagMs();
            case CONNECTION_UTILIZATION_PCT -> snapshot.getConnectionUtilizationPct();
            case STORAGE_UTILIZATION_PCT -> snapshot.getStorageUtilizationPct();
            case OPLOG_WINDOW_HOURS -> snapshot.getOplogWindowHours();
            case SLOW_QUERY_COUNT -> snapshot.getSlowQueryCount();
            case OPS_PER_SECOND -> snapshot.getOpsPerSecond();
            case VOTING_MEMBERS_DOWN ->
                    snapshot.getTotalVotingMembers() - snapshot.getVotingMembersUp();
        };
    }

    public boolean evaluateThreshold(double actual, AlertRule.Comparator comparator, double threshold) {
        return switch (comparator) {
            case GREATER_THAN -> actual > threshold;
            case LESS_THAN -> actual < threshold;
            case EQUALS -> Math.abs(actual - threshold) < 0.001;
        };
    }

    private boolean isInCooldown(AlertRule rule) {
        if (rule.getLastFiredAt() == null) return false;
        Duration elapsed = Duration.between(rule.getLastFiredAt(), Instant.now());
        return elapsed.toMinutes() < rule.getCooldownMinutes();
    }

    private Alert fireAlert(String clusterId, AlertRule rule, double actualValue) {
        // Update rule's last fired timestamp
        rule.setLastFiredAt(Instant.now());
        ruleRepository.save(rule);

        String message = String.format("[%s] %s: actual=%.2f, threshold=%.2f (%s)",
                rule.getSeverity(), rule.getRuleName(), actualValue,
                rule.getThreshold(), rule.getComparator());

        Alert alert = Alert.builder()
                .clusterId(clusterId)
                .ruleId(rule.getId())
                .ruleName(rule.getRuleName())
                .severity(rule.getSeverity())
                .metricType(rule.getMetricType())
                .actualValue(actualValue)
                .thresholdValue(rule.getThreshold())
                .message(message)
                .status(Alert.AlertStatus.OPEN)
                .firedAt(Instant.now())
                .build();

        return alertRepository.save(alert);
    }

    public Alert acknowledgeAlert(String alertId, String acknowledgedBy) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + alertId));

        alert.setStatus(Alert.AlertStatus.ACKNOWLEDGED);
        alert.setAcknowledgedAt(Instant.now());
        alert.setAcknowledgedBy(acknowledgedBy);

        return alertRepository.save(alert);
    }

    public List<Alert> getAllAlerts() {
        return alertRepository.findAll();
    }

    public AlertRule createRule(String clusterId, AlertRule rule) {
        rule.setClusterId(clusterId);
        return ruleRepository.save(rule);
    }

    public List<AlertRule> getRulesForCluster(String clusterId) {
        return ruleRepository.findByClusterId(clusterId);
    }
}
