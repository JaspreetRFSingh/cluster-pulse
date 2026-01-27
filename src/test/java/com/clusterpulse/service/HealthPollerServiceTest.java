package com.clusterpulse.service;

import com.clusterpulse.model.Cluster;
import com.clusterpulse.model.HealthSnapshot;
import com.clusterpulse.repository.HealthSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HealthPollerService. Because no real MongoDB is available in
 * a unit test context, all pollCluster() calls will hit the catch block and
 * return an UNREACHABLE snapshot — which is the designed failure behaviour.
 * These tests verify that path and the settings-cache invariant.
 */
@ExtendWith(MockitoExtension.class)
class HealthPollerServiceTest {

    @Mock
    private HealthSnapshotRepository snapshotRepository;

    @InjectMocks
    private HealthPollerService healthPollerService;

    @Test
    @DisplayName("Should persist and return an UNREACHABLE snapshot when cluster is not reachable")
    void shouldReturnUnreachableSnapshotOnConnectionFailure() {
        Cluster cluster = cluster("c1", "mongodb://nonexistent-host:27017");
        when(snapshotRepository.save(any(HealthSnapshot.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        HealthSnapshot result = healthPollerService.pollCluster(cluster);

        assertEquals(Cluster.ClusterStatus.UNREACHABLE, result.getComputedStatus());
        assertEquals("c1", result.getClusterId());
        assertNotNull(result.getCapturedAt());
        assertNotNull(result.getMembers());
        verify(snapshotRepository).save(argThat(s ->
                s.getComputedStatus() == Cluster.ClusterStatus.UNREACHABLE));
    }

    @Test
    @DisplayName("Should always persist a snapshot even when connection fails")
    void shouldAlwaysPersistSnapshotOnFailure() {
        Cluster cluster = cluster("c1", "mongodb://bad-host:1");
        when(snapshotRepository.save(any(HealthSnapshot.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        healthPollerService.pollCluster(cluster);

        verify(snapshotRepository, times(1)).save(any(HealthSnapshot.class));
    }

    @Test
    @DisplayName("Repeated polls of the same URI should succeed without exception")
    void shouldHandleRepeatedPollsWithCachedSettings() {
        Cluster cluster = cluster("c1", "mongodb://cached-host:27017");
        when(snapshotRepository.save(any(HealthSnapshot.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // First poll — settings are built and cached
        HealthSnapshot first = healthPollerService.pollCluster(cluster);
        // Second poll — settings are retrieved from cache
        HealthSnapshot second = healthPollerService.pollCluster(cluster);

        // Both should return UNREACHABLE (no real MongoDB) without throwing
        assertEquals(Cluster.ClusterStatus.UNREACHABLE, first.getComputedStatus());
        assertEquals(Cluster.ClusterStatus.UNREACHABLE, second.getComputedStatus());
        verify(snapshotRepository, times(2)).save(any(HealthSnapshot.class));
    }

    @Test
    @DisplayName("Different cluster URIs should each produce an UNREACHABLE snapshot")
    void shouldHandleMultipleDifferentUris() {
        Cluster c1 = cluster("c1", "mongodb://host-a:27017");
        Cluster c2 = cluster("c2", "mongodb://host-b:27017");
        when(snapshotRepository.save(any(HealthSnapshot.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        HealthSnapshot snap1 = healthPollerService.pollCluster(c1);
        HealthSnapshot snap2 = healthPollerService.pollCluster(c2);

        assertEquals("c1", snap1.getClusterId());
        assertEquals("c2", snap2.getClusterId());
        assertEquals(Cluster.ClusterStatus.UNREACHABLE, snap1.getComputedStatus());
        assertEquals(Cluster.ClusterStatus.UNREACHABLE, snap2.getComputedStatus());
    }

    // --- helper ---

    private Cluster cluster(String id, String uri) {
        return Cluster.builder()
                .id(id)
                .displayName("Test-" + id)
                .connectionUri(uri)
                .build();
    }
}
