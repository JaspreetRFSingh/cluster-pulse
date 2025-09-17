package com.clusterpulse.repository;

import com.clusterpulse.model.Incident;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IncidentRepository extends MongoRepository<Incident, String> {
    List<Incident> findByClusterIdOrderByOpenedAtDesc(String clusterId);
    List<Incident> findByStatus(Incident.IncidentStatus status);
    Optional<Incident> findFirstByClusterIdAndStatusOrderByOpenedAtDesc(
            String clusterId, Incident.IncidentStatus status);
}
