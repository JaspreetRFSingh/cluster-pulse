package com.clusterpulse.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An incident represents a durable operational record — a period of degraded
 * or critical cluster health that may involve multiple alerts and remediation actions.
 * Supports full lifecycle tracking for post-mortem analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "incidents")
public class Incident {

    @Id
    private String id;

    @Indexed
    private String clusterId;

    private String title;
    private String description;
    private AlertRule.Severity severity;

    @Builder.Default
    private IncidentStatus status = IncidentStatus.OPEN;

    @Builder.Default
    private List<String> alertIds = new ArrayList<>();

    @Builder.Default
    private List<TimelineEntry> timeline = new ArrayList<>();

    private Instant openedAt;
    private Instant resolvedAt;
    private String resolvedBy;
    private String resolution;

    private long durationSeconds;

    public enum IncidentStatus {
        OPEN, INVESTIGATING, MITIGATING, RESOLVED, POST_MORTEM
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelineEntry {
        private Instant timestamp;
        private String action;
        private String actor;    // "system", "auto-remediation", or username
        private String details;
    }

    public void addTimelineEntry(String action, String actor, String details) {
        if (this.timeline == null) {
            this.timeline = new ArrayList<>();
        }
        this.timeline.add(TimelineEntry.builder()
                .timestamp(Instant.now())
                .action(action)
                .actor(actor)
                .details(details)
                .build());
    }
}
