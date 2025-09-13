package com.clusterpulse.repository;

import com.clusterpulse.model.HealthSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface HealthSnapshotRepository extends MongoRepository<HealthSnapshot, String> {
    Optional<HealthSnapshot> findFirstByClusterIdOrderByCapturedAtDesc(String clusterId);
    List<HealthSnapshot> findByClusterIdOrderByCapturedAtDesc(String clusterId, Pageable pageable);
    List<HealthSnapshot> findByClusterIdAndCapturedAtBetweenOrderByCapturedAtDesc(
            String clusterId, Instant from, Instant to);
    void deleteByClusterIdAndCapturedAtBefore(String clusterId, Instant before);
}
