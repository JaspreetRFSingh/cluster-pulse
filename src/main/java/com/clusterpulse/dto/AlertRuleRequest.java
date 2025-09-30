package com.clusterpulse.dto;

import com.clusterpulse.model.AlertRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRuleRequest {

    @NotBlank(message = "Rule name is required")
    private String ruleName;

    @NotNull(message = "Metric type is required")
    private AlertRule.MetricType metricType;

    @NotNull(message = "Comparator is required")
    private AlertRule.Comparator comparator;

    @Positive(message = "Threshold must be positive")
    private double threshold;

    @NotNull(message = "Severity is required")
    private AlertRule.Severity severity;

    @Builder.Default
    private int cooldownMinutes = 5;
}
