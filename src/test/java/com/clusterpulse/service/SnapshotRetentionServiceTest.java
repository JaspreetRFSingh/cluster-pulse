package com.clusterpulse.service;

import com.clusterpulse.model.Cluster;
import com.clusterpulse.repository.HealthSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SnapshotRetentionServiceTest {

    @Mock private ClusterRegistryService clusterRegistryService;
    @Mock private HealthSnapshotRepository snapshotRepository;

    @InjectMocks
    private SnapshotRetentionService retentionService;

    @BeforeEach
    void setUp() {
        // @Value fields are not injected by Mockito — set via reflection
        ReflectionTestUtils.setField(retentionService, "retentionHours", 72);
    }

    @Test
    @DisplayName("Should skip cleanup when no clusters are registered")
    void shouldSkipWhenNoClusters() {
        when(clusterRegistryService.findAllClusters()).thenReturn(List.of());

        retentionService.cleanupExpiredSnapshots();

        verify(snapshotRepository, never()).deleteByClusterIdAndCapturedAtBefore(any(), any());
    }

    @Test
    @DisplayName("Should call delete for each registered cluster")
    void shouldDeleteForEachCluster() {
        Cluster c1 = cluster("c1", "Cluster-1");
        Cluster c2 = cluster("c2", "Cluster-2");
        Cluster c3 = cluster("c3", "Cluster-3");
        when(clusterRegistryService.findAllClusters()).thenReturn(List.of(c1, c2, c3));

        retentionService.cleanupExpiredSnapshots();

        verify(snapshotRepository, times(3)).deleteByClusterIdAndCapturedAtBefore(any(), any());
        verify(snapshotRepository).deleteByClusterIdAndCapturedAtBefore(eq("c1"), any());
        verify(snapshotRepository).deleteByClusterIdAndCapturedAtBefore(eq("c2"), any());
        verify(snapshotRepository).deleteByClusterIdAndCapturedAtBefore(eq("c3"), any());
    }

    @Test
    @DisplayName("Should use a cutoff approximately equal to now minus retention hours")
    void shouldUseCutoffBasedOnRetentionHours() {
        Cluster c1 = cluster("c1", "Test-Cluster");
        when(clusterRegistryService.findAllClusters()).thenReturn(List.of(c1));

        Instant before = Instant.now();
        retentionService.cleanupExpiredSnapshots();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(snapshotRepository).deleteByClusterIdAndCapturedAtBefore(eq("c1"), cutoffCaptor.capture());

        Instant cutoff = cutoffCaptor.getValue();
        // Cutoff should be between (after - 73h) and (before - 71h)
        assertTrue(cutoff.isAfter(after.minus(73, ChronoUnit.HOURS)),
                "Cutoff should be within a 2-hour window of 72h ago");
        assertTrue(cutoff.isBefore(before.minus(71, ChronoUnit.HOURS)),
                "Cutoff should be at least 71 hours ago");
    }

    @Test
    @DisplayName("Should continue cleanup for remaining clusters when one throws")
    void shouldContinueCleanupOnPartialFailure() {
        Cluster c1 = cluster("c1", "Failing-Cluster");
        Cluster c2 = cluster("c2", "Good-Cluster");
        when(clusterRegistryService.findAllClusters()).thenReturn(List.of(c1, c2));
        doThrow(new RuntimeException("DB error"))
                .when(snapshotRepository)
                .deleteByClusterIdAndCapturedAtBefore(eq("c1"), any());

        assertDoesNotThrow(() -> retentionService.cleanupExpiredSnapshots());

        // c2 must still be cleaned even though c1 threw
        verify(snapshotRepository).deleteByClusterIdAndCapturedAtBefore(eq("c2"), any());
    }

    @Test
    @DisplayName("Should respect custom retention window when retentionHours is overridden")
    void shouldRespectCustomRetentionHours() {
        ReflectionTestUtils.setField(retentionService, "retentionHours", 24);
        Cluster c1 = cluster("c1", "Short-TTL-Cluster");
        when(clusterRegistryService.findAllClusters()).thenReturn(List.of(c1));

        retentionService.cleanupExpiredSnapshots();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(snapshotRepository).deleteByClusterIdAndCapturedAtBefore(eq("c1"), cutoffCaptor.capture());

        Instant cutoff = cutoffCaptor.getValue();
        Instant expectedApprox = Instant.now().minus(24, ChronoUnit.HOURS);
        // Cutoff should be within 2 minutes of 24h ago
        assertTrue(Math.abs(cutoff.toEpochMilli() - expectedApprox.toEpochMilli()) < 120_000,
                "Cutoff should be approximately 24 hours ago");
    }

    // --- helper ---

    private Cluster cluster(String id, String name) {
        return Cluster.builder().id(id).displayName(name).build();
    }
}
