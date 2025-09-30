package com.clusterpulse.dto;

import com.clusterpulse.model.AlertRule;
import com.clusterpulse.model.Incident;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentResponse {

    private String id;
    private String clusterId;
    private String title;
    private String description;
    private AlertRule.Severity severity;
    private Incident.IncidentStatus status;
    private List<String> alertIds;
    private List<Incident.TimelineEntry> timeline;
    private Instant openedAt;
    private Instant resolvedAt;
    private String resolution;
    private long durationSeconds;

    public static IncidentResponse from(Incident incident) {
        return IncidentResponse.builder()
                .id(incident.getId())
                .clusterId(incident.getClusterId())
                .title(incident.getTitle())
                .description(incident.getDescription())
                .severity(incident.getSeverity())
                .status(incident.getStatus())
                .alertIds(incident.getAlertIds())
                .timeline(incident.getTimeline())
                .openedAt(incident.getOpenedAt())
                .resolvedAt(incident.getResolvedAt())
                .resolution(incident.getResolution())
                .durationSeconds(incident.getDurationSeconds())
                .build();
    }
}
