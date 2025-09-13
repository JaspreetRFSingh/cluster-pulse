package com.clusterpulse.repository;

import com.clusterpulse.model.Cluster;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClusterRepository extends MongoRepository<Cluster, String> {
    List<Cluster> findByEnvironment(String environment);
    List<Cluster> findByStatus(Cluster.ClusterStatus status);
    boolean existsByConnectionUri(String connectionUri);
}
