package com.clusterpulse.scheduler;

import com.clusterpulse.model.Alert;
import com.clusterpulse.model.AlertRule;
import com.clusterpulse.model.Cluster;
import com.clusterpulse.model.HealthSnapshot;
import com.clusterpulse.service.AlertEngineService;
import com.clusterpulse.service.ClusterRegistryService;
import com.clusterpulse.service.HealthPollerService;
import com.clusterpulse.service.RemediationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthCheckSchedulerTest {

    @Mock private ClusterRegistryService clusterRegistryService;
    @Mock private HealthPollerService healthPollerService;
    @Mock private AlertEngineService alertEngineService;
    @Mock private RemediationService remediationService;

    private HealthCheckScheduler scheduler;

    @BeforeEach
    void setUp() {
        // Direct executor runs tasks on the calling thread — keeps tests synchronous
        Executor directExecutor = Runnable::run;
        scheduler = new HealthCheckScheduler(
                clusterRegistryService, healthPollerService,
                alertEngineService, remediationService, directExecutor);
    }

    @Test
    @DisplayName("Should skip cycle when no clusters are registered")
    void shouldSkipCycleWhenNoClusters() {
        when(clusterRegistryService.findAllClusters()).thenReturn(List.of());

        scheduler.runHealthCheckCycle();

        verify(healthPollerService, never()).pollCluster(any());
        verify(alertEngineService, never()).evaluateRules(any(), any());
    }

    @Test
    @DisplayName("Should poll every registered cluster in the cycle")
    void shouldPollAllRegisteredClusters() {
        Cluster c1 = cluster("c1", "Cluster-1");
        Cluster c2 = cluster("c2", "Cluster-2");
        HealthSnapshot snap = healthySnapshot("c1");

        when(clusterRegistryService.findAllClusters()).thenReturn(List.of(c1, c2));
        when(healthPollerService.pollCluster(any())).thenReturn(snap);
        when(alertEngineService.evaluateRules(any(), any())).thenReturn(List.of());
        when(clusterRegistryService.updateClusterHealth(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(c1);

        scheduler.runHealthCheckCycle();

        verify(healthPollerService, times(2)).pollCluster(any());
        verify(alertEngineService, times(2)).evaluateRules(any(), eq(snap));
    }

    @Test
    @DisplayName("Should continue polling remaining clusters when one fails")
    void shouldContinueWhenOneClusterFails() {
        Cluster c1 = cluster("c1", "Bad-Cluster");
        Cluster c2 = cluster("c2", "Good-Cluster");
        HealthSnapshot snap = healthySnapshot("c2");

        when(clusterRegistryService.findAllClusters()).thenReturn(List.of(c1, c2));
        when(healthPollerService.pollCluster(c1))
                .thenThrow(new RuntimeException("connection refused"));
        when(healthPollerService.pollCluster(c2)).thenReturn(snap);
        when(alertEngineService.evaluateRules(any(), any())).thenReturn(List.of());
        when(clusterRegistryService.updateClusterHealth(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(c2);

        assertDoesNotThrow(() -> scheduler.runHealthCheckCycle());

        verify(healthPollerService, times(2)).pollCluster(any());
        // c2 should still be evaluated even though c1 threw
        verify(alertEngineService, times(1)).evaluateRules(eq("c2"), eq(snap));
    }

    @Test
    @DisplayName("Should invoke remediation when critical alerts fire")
    void shouldTriggerRemediationForCriticalAlerts() {
        Cluster c1 = cluster("c1", "Prod-RS");
        c1.setRemediationEnabled(true);
        HealthSnapshot snap = healthySnapshot("c1");
        Alert critical = Alert.builder()
                .id("a1")
                .clusterId("c1")
                .severity(AlertRule.Severity.CRITICAL)
                .build();

        when(clusterRegistryService.findAllClusters()).thenReturn(List.of(c1));
        when(healthPollerService.pollCluster(c1)).thenReturn(snap);
        when(alertEngineService.evaluateRules("c1", snap)).thenReturn(List.of(critical));
        when(clusterRegistryService.updateClusterHealth(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(c1);

        scheduler.runHealthCheckCycle();

        verify(remediationService).evaluateAndRemediate(c1, List.of(critical));
    }

    @Test
    @DisplayName("Should not invoke remediation when no alerts fire")
    void shouldNotTriggerRemediationWithNoAlerts() {
        Cluster c1 = cluster("c1", "Healthy-RS");
        HealthSnapshot snap = healthySnapshot("c1");

        when(clusterRegistryService.findAllClusters()).thenReturn(List.of(c1));
        when(healthPollerService.pollCluster(c1)).thenReturn(snap);
        when(alertEngineService.evaluateRules("c1", snap)).thenReturn(List.of());
        when(clusterRegistryService.updateClusterHealth(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(c1);

        scheduler.runHealthCheckCycle();

        verify(remediationService, never()).evaluateAndRemediate(any(), any());
    }

    // --- helpers ---

    private Cluster cluster(String id, String name) {
        return Cluster.builder().id(id).displayName(name).build();
    }

    private HealthSnapshot healthySnapshot(String clusterId) {
        return HealthSnapshot.builder()
                .clusterId(clusterId)
                .computedStatus(Cluster.ClusterStatus.HEALTHY)
                .members(List.of())
                .build();
    }
}
