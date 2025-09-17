package com.clusterpulse.repository;

import com.clusterpulse.model.AlertRule;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRuleRepository extends MongoRepository<AlertRule, String> {
    List<AlertRule> findByClusterId(String clusterId);
    List<AlertRule> findByClusterIdAndEnabled(String clusterId, boolean enabled);
}
