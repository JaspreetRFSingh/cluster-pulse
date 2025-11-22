package com.clusterpulse.controller;

import com.clusterpulse.dto.ClusterRegistrationRequest;
import com.clusterpulse.dto.ClusterStatusResponse;
import com.clusterpulse.model.Cluster;
import com.clusterpulse.model.HealthSnapshot;
import com.clusterpulse.repository.HealthSnapshotRepository;
import com.clusterpulse.service.ClusterRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/clusters")
@RequiredArgsConstructor
@Tag(name = "Clusters", description = "Cluster registration and status management")
public class ClusterController {

    private final ClusterRegistryService clusterRegistryService;
    private final HealthSnapshotRepository healthSnapshotRepository;

    @PostMapping
    @Operation(summary = "Register a new MongoDB cluster for monitoring")
    public ResponseEntity<Cluster> registerCluster(
            @Valid @RequestBody ClusterRegistrationRequest request) {
        Cluster cluster = clusterRegistryService.registerCluster(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(cluster);
    }

    @GetMapping
    @Operation(summary = "List all registered clusters with their current status")
    public ResponseEntity<List<ClusterStatusResponse>> listClusters() {
        return ResponseEntity.ok(clusterRegistryService.listAllClusters());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get detailed cluster status including latest health summary")
    public ResponseEntity<ClusterStatusResponse> getCluster(@PathVariable String id) {
        return ResponseEntity.ok(clusterRegistryService.getClusterStatus(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deregister a cluster from monitoring")
    public ResponseEntity<Void> deregisterCluster(@PathVariable String id) {
        clusterRegistryService.deregisterCluster(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/health")
    @Operation(summary = "Get the latest health snapshot for a cluster")
    public ResponseEntity<HealthSnapshot> getLatestHealth(@PathVariable String id) {
        // Validate cluster exists
        clusterRegistryService.findClusterOrThrow(id);

        return healthSnapshotRepository
                .findFirstByClusterIdOrderByCapturedAtDesc(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}/health/history")
    @Operation(summary = "Get historical health snapshots for a cluster")
    public ResponseEntity<List<HealthSnapshot>> getHealthHistory(
            @PathVariable String id,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "50") int limit) {

        clusterRegistryService.findClusterOrThrow(id);

        Instant from = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<HealthSnapshot> history = healthSnapshotRepository
                .findByClusterIdAndCapturedAtBetweenOrderByCapturedAtDesc(
                        id, from, Instant.now());

        // Apply limit
        if (history.size() > limit) {
            history = history.subList(0, limit);
        }

        return ResponseEntity.ok(history);
    }
}
