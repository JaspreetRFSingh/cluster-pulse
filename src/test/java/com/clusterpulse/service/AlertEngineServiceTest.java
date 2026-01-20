package com.clusterpulse.service;

import com.clusterpulse.model.Alert;
import com.clusterpulse.model.AlertRule;
import com.clusterpulse.model.Cluster;
import com.clusterpulse.model.HealthSnapshot;
import com.clusterpulse.repository.AlertRepository;
import com.clusterpulse.repository.AlertRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertEngineServiceTest {

    @Mock
    private AlertRuleRepository ruleRepository;

    @Mock
    private AlertRepository alertRepository;

    @InjectMocks
    private AlertEngineService alertEngineService;

    private HealthSnapshot healthySnapshot;
    private HealthSnapshot degradedSnapshot;

    @BeforeEach
    void setUp() {
        healthySnapshot = HealthSnapshot.builder()
                .clusterId("cluster-1")
                .capturedAt(Instant.now())
                .maxReplicationLagMs(500)
                .connectionUtilizationPct(45.0)
                .storageUtilizationPct(60.0)
                .oplogWindowHours(48)
                .slowQueryCount(2)
                .opsPerSecond(5000)
                .votingMembersUp(3)
                .totalVotingMembers(3)
                .computedStatus(Cluster.ClusterStatus.HEALTHY)
                .build();

        degradedSnapshot = HealthSnapshot.builder()
                .clusterId("cluster-1")
                .capturedAt(Instant.now())
                .maxReplicationLagMs(15000)
                .connectionUtilizationPct(82.0)
                .storageUtilizationPct(91.0)
                .oplogWindowHours(12)
                .slowQueryCount(50)
                .opsPerSecond(25000)
                .votingMembersUp(2)
                .totalVotingMembers(3)
                .computedStatus(Cluster.ClusterStatus.DEGRADED)
                .build();
    }

    @Test
    @DisplayName("Should fire alert when replication lag exceeds threshold")
    void shouldFireAlertOnHighReplicationLag() {
        AlertRule rule = AlertRule.builder()
                .id("rule-1")
                .clusterId("cluster-1")
                .ruleName("High Replication Lag")
                .metricType(AlertRule.MetricType.REPLICATION_LAG_MS)
                .comparator(AlertRule.Comparator.GREATER_THAN)
                .threshold(10000)
                .severity(AlertRule.Severity.WARNING)
                .enabled(true)
                .cooldownMinutes(5)
                .build();

        when(ruleRepository.findByClusterIdAndEnabled("cluster-1", true))
                .thenReturn(List.of(rule));
        when(ruleRepository.save(any(AlertRule.class))).thenReturn(rule);
        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Alert> alerts = alertEngineService.evaluateRules("cluster-1", degradedSnapshot);

        assertEquals(1, alerts.size());
        assertEquals("High Replication Lag", alerts.get(0).getRuleName());
        assertEquals(AlertRule.Severity.WARNING, alerts.get(0).getSeverity());
        assertEquals(15000, alerts.get(0).getActualValue());
    }

    @Test
    @DisplayName("Should not fire alert when metric is within threshold")
    void shouldNotFireAlertWhenHealthy() {
        AlertRule rule = AlertRule.builder()
                .id("rule-1")
                .clusterId("cluster-1")
                .ruleName("High Replication Lag")
                .metricType(AlertRule.MetricType.REPLICATION_LAG_MS)
                .comparator(AlertRule.Comparator.GREATER_THAN)
                .threshold(10000)
                .severity(AlertRule.Severity.WARNING)
                .enabled(true)
                .cooldownMinutes(5)
                .build();

        when(ruleRepository.findByClusterIdAndEnabled("cluster-1", true))
                .thenReturn(List.of(rule));

        List<Alert> alerts = alertEngineService.evaluateRules("cluster-1", healthySnapshot);

        assertEquals(0, alerts.size());
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should skip rule during cooldown period")
    void shouldSkipRuleDuringCooldown() {
        AlertRule rule = AlertRule.builder()
                .id("rule-1")
                .clusterId("cluster-1")
                .ruleName("High Replication Lag")
                .metricType(AlertRule.MetricType.REPLICATION_LAG_MS)
                .comparator(AlertRule.Comparator.GREATER_THAN)
                .threshold(10000)
                .severity(AlertRule.Severity.WARNING)
                .enabled(true)
                .cooldownMinutes(5)
                .lastFiredAt(Instant.now().minus(2, ChronoUnit.MINUTES))  // Fired 2 min ago
                .build();

        when(ruleRepository.findByClusterIdAndEnabled("cluster-1", true))
                .thenReturn(List.of(rule));

        List<Alert> alerts = alertEngineService.evaluateRules("cluster-1", degradedSnapshot);

        assertEquals(0, alerts.size());
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should fire after cooldown expires")
    void shouldFireAfterCooldownExpires() {
        AlertRule rule = AlertRule.builder()
                .id("rule-1")
                .clusterId("cluster-1")
                .ruleName("High Replication Lag")
                .metricType(AlertRule.MetricType.REPLICATION_LAG_MS)
                .comparator(AlertRule.Comparator.GREATER_THAN)
                .threshold(10000)
                .severity(AlertRule.Severity.WARNING)
                .enabled(true)
                .cooldownMinutes(5)
                .lastFiredAt(Instant.now().minus(10, ChronoUnit.MINUTES))  // Fired 10 min ago
                .build();

        when(ruleRepository.findByClusterIdAndEnabled("cluster-1", true))
                .thenReturn(List.of(rule));
        when(ruleRepository.save(any(AlertRule.class))).thenReturn(rule);
        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Alert> alerts = alertEngineService.evaluateRules("cluster-1", degradedSnapshot);

        assertEquals(1, alerts.size());
    }

    @Test
    @DisplayName("Should correctly extract all metric types from snapshot")
    void shouldExtractAllMetricTypes() {
        assertEquals(15000,
                alertEngineService.extractMetricValue(degradedSnapshot, AlertRule.MetricType.REPLICATION_LAG_MS));
        assertEquals(82.0,
                alertEngineService.extractMetricValue(degradedSnapshot, AlertRule.MetricType.CONNECTION_UTILIZATION_PCT));
        assertEquals(91.0,
                alertEngineService.extractMetricValue(degradedSnapshot, AlertRule.MetricType.STORAGE_UTILIZATION_PCT));
        assertEquals(12,
                alertEngineService.extractMetricValue(degradedSnapshot, AlertRule.MetricType.OPLOG_WINDOW_HOURS));
        assertEquals(1,
                alertEngineService.extractMetricValue(degradedSnapshot, AlertRule.MetricType.VOTING_MEMBERS_DOWN));
    }

    @Test
    @DisplayName("Should evaluate all comparator types correctly")
    void shouldEvaluateComparators() {
        assertTrue(alertEngineService.evaluateThreshold(100, AlertRule.Comparator.GREATER_THAN, 50));
        assertFalse(alertEngineService.evaluateThreshold(30, AlertRule.Comparator.GREATER_THAN, 50));

        assertTrue(alertEngineService.evaluateThreshold(10, AlertRule.Comparator.LESS_THAN, 50));
        assertFalse(alertEngineService.evaluateThreshold(80, AlertRule.Comparator.LESS_THAN, 50));

        assertTrue(alertEngineService.evaluateThreshold(50, AlertRule.Comparator.EQUALS, 50));
        assertFalse(alertEngineService.evaluateThreshold(51, AlertRule.Comparator.EQUALS, 50));
    }

    @Test
    @DisplayName("Should acknowledge alert successfully")
    void shouldAcknowledgeAlert() {
        Alert alert = Alert.builder()
                .id("alert-1")
                .status(Alert.AlertStatus.OPEN)
                .build();

        when(alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

        Alert result = alertEngineService.acknowledgeAlert("alert-1", "jaspreet");

        assertEquals(Alert.AlertStatus.ACKNOWLEDGED, result.getStatus());
        assertEquals("jaspreet", result.getAcknowledgedBy());
        assertNotNull(result.getAcknowledgedAt());
    }
}
