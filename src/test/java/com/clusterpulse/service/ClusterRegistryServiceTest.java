package com.clusterpulse.service;

import com.clusterpulse.dto.ClusterRegistrationRequest;
import com.clusterpulse.dto.ClusterStatusResponse;
import com.clusterpulse.exception.ClusterNotFoundException;
import com.clusterpulse.model.Cluster;
import com.clusterpulse.model.HealthSnapshot;
import com.clusterpulse.repository.ClusterRepository;
import com.clusterpulse.repository.HealthSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClusterRegistryServiceTest {

    @Mock
    private ClusterRepository clusterRepository;

    @Mock
    private HealthSnapshotRepository healthSnapshotRepository;

    @InjectMocks
    private ClusterRegistryService clusterRegistryService;

    @Test
    @DisplayName("Should register a new cluster successfully")
    void shouldRegisterCluster() {
        ClusterRegistrationRequest request = ClusterRegistrationRequest.builder()
                .displayName("Production RS-1")
                .connectionUri("mongodb://prod-rs1:27017")
                .environment("PRODUCTION")
                .remediationEnabled(false)
                .tags(Map.of("team", "platform"))
                .build();

        when(clusterRepository.existsByConnectionUri(any())).thenReturn(false);
        when(clusterRepository.save(any(Cluster.class))).thenAnswer(inv -> {
            Cluster c = inv.getArgument(0);
            c.setId("generated-id");
            return c;
        });

        Cluster result = clusterRegistryService.registerCluster(request);

        assertNotNull(result.getId());
        assertEquals("Production RS-1", result.getDisplayName());
        assertEquals("PRODUCTION", result.getEnvironment());
        assertEquals(Cluster.ClusterStatus.UNKNOWN, result.getStatus());
        verify(clusterRepository).save(any(Cluster.class));
    }

    @Test
    @DisplayName("Should reject duplicate connection URI")
    void shouldRejectDuplicateUri() {
        ClusterRegistrationRequest request = ClusterRegistrationRequest.builder()
                .displayName("Duplicate")
                .connectionUri("mongodb://existing:27017")
                .environment("DEVELOPMENT")
                .build();

        when(clusterRepository.existsByConnectionUri("mongodb://existing:27017"))
                .thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> clusterRegistryService.registerCluster(request));

        verify(clusterRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should return cluster status with health summary")
    void shouldReturnClusterWithHealth() {
        Cluster cluster = Cluster.builder()
                .id("cluster-1")
                .displayName("Test Cluster")
                .environment("STAGING")
                .status(Cluster.ClusterStatus.HEALTHY)
                .build();

        HealthSnapshot snapshot = HealthSnapshot.builder()
                .clusterId("cluster-1")
                .maxReplicationLagMs(200)
                .connectionUtilizationPct(30.0)
                .storageUtilizationPct(55.0)
                .votingMembersUp(3)
                .totalVotingMembers(3)
                .build();

        when(clusterRepository.findById("cluster-1")).thenReturn(Optional.of(cluster));
        when(healthSnapshotRepository.findFirstByClusterIdOrderByCapturedAtDesc("cluster-1"))
                .thenReturn(Optional.of(snapshot));

        ClusterStatusResponse response = clusterRegistryService.getClusterStatus("cluster-1");

        assertEquals("Test Cluster", response.getDisplayName());
        assertNotNull(response.getLatestHealth());
        assertEquals(200, response.getLatestHealth().getMaxReplicationLagMs());
        assertEquals(3, response.getLatestHealth().getVotingMembersUp());
    }

    @Test
    @DisplayName("Should throw exception for non-existent cluster")
    void shouldThrowForMissingCluster() {
        when(clusterRepository.findById("nonexistent")).thenReturn(Optional.empty());

        assertThrows(ClusterNotFoundException.class,
                () -> clusterRegistryService.getClusterStatus("nonexistent"));
    }

    @Test
    @DisplayName("Should deregister cluster successfully")
    void shouldDeregisterCluster() {
        Cluster cluster = Cluster.builder()
                .id("cluster-1")
                .displayName("To Delete")
                .build();

        when(clusterRepository.findById("cluster-1")).thenReturn(Optional.of(cluster));

        clusterRegistryService.deregisterCluster("cluster-1");

        verify(clusterRepository).delete(cluster);
    }

    @Test
    @DisplayName("Should update cluster health status")
    void shouldUpdateClusterHealth() {
        Cluster cluster = Cluster.builder()
                .id("cluster-1")
                .displayName("Test")
                .status(Cluster.ClusterStatus.UNKNOWN)
                .build();

        when(clusterRepository.findById("cluster-1")).thenReturn(Optional.of(cluster));
        when(clusterRepository.save(any(Cluster.class))).thenAnswer(inv -> inv.getArgument(0));

        Cluster updated = clusterRegistryService.updateClusterHealth(
                "cluster-1", Cluster.ClusterStatus.HEALTHY,
                "rs0", 3, "primary:27017", "7.0.5");

        assertEquals(Cluster.ClusterStatus.HEALTHY, updated.getStatus());
        assertEquals("rs0", updated.getReplicaSetName());
        assertEquals(3, updated.getMemberCount());
        assertNotNull(updated.getLastHealthCheckAt());
    }
}
