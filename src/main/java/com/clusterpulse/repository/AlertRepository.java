package com.clusterpulse.repository;

import com.clusterpulse.model.Alert;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRepository extends MongoRepository<Alert, String> {
    List<Alert> findByClusterIdOrderByFiredAtDesc(String clusterId);
    List<Alert> findByStatus(Alert.AlertStatus status);
    List<Alert> findByClusterIdAndStatus(String clusterId, Alert.AlertStatus status);
}
