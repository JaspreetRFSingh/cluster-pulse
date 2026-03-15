# ClusterPulse — System Design

## Table of Contents

1. [Overview](#1-overview)
2. [Goals and Non-Goals](#2-goals-and-non-goals)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Component Breakdown](#4-component-breakdown)
5. [Core Pipeline: Health Check Cycle](#5-core-pipeline-health-check-cycle)
6. [Data Model](#6-data-model)
7. [Alert Engine Design](#7-alert-engine-design)
8. [Remediation Engine Design](#8-remediation-engine-design)
9. [API Design](#9-api-design)
10. [Storage Design](#10-storage-design)
11. [Threading and Concurrency](#11-threading-and-concurrency)
12. [Failure Modes and Resilience](#12-failure-modes-and-resilience)
13. [Configuration Reference](#13-configuration-reference)
14. [Scalability Considerations](#14-scalability-considerations)
15. [Performance Optimizations (Dec 2025 – Feb 2026)](#15-performance-optimizations-dec-2025--feb-2026)

---

## 1. Overview

ClusterPulse is a MongoDB replica set health monitoring and auto-remediation engine. It registers external MongoDB clusters, continuously polls their health metrics, evaluates configurable alert rules, and optionally executes healing actions — analogous to the cluster management layer in MongoDB Atlas.

**Core value:** A single ClusterPulse instance can monitor many MongoDB deployments across environments (production, staging, development) from one control plane, with a uniform REST API for cluster management, alerting, and incident tracking.

---

## 2. Goals and Non-Goals

### Goals
- Poll any reachable MongoDB replica set on a configurable interval
- Capture point-in-time health snapshots (replication lag, connection utilization, storage, ops/sec)
- Evaluate per-cluster alert rules with configurable thresholds and cooldown windows
- Escalate repeated alerts into durable incidents with audit timelines
- Execute automated remediation actions on critical failures (opt-in, dry-run by default)
- Expose a REST API for all management operations

### Non-Goals
- **Not a time-series database.** Snapshots are retained for 72 hours by default; ClusterPulse is not a long-term metrics store. For historical analysis, export snapshots to a dedicated TSDB.
- **Not a MongoDB proxy or query router.** ClusterPulse connects only to admin commands (`replSetGetStatus`, `serverStatus`) — it never handles application traffic.
- **Not multi-tenant.** There is no user authentication or tenant isolation in the current design. All users of the REST API share the same view.
- **Not a replacement for Ops Manager.** ClusterPulse is purpose-built for lightweight observability; it does not manage backups, schema, or provisioning.

---

## 3. High-Level Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                        ClusterPulse Service                        │
│                      (Spring Boot 3.2 / Java 17)                   │
│                                                                    │
│  ┌──────────────┐    ┌────────────────────────────────────────┐   │
│  │  REST API    │    │          Health Check Pipeline         │   │
│  │  Controllers │    │                                        │   │
│  │              │    │  HealthCheckScheduler  (every 30s)     │   │
│  │  /clusters   │    │         │                              │   │
│  │  /alerts     │    │         ▼                              │   │
│  │  /incidents  │    │  HealthPollerService                   │   │
│  └──────┬───────┘    │         │ replSetGetStatus             │   │
│         │            │         │ serverStatus                 │   │
│         │            │         ▼                              │   │
│         │            │  AlertEngineService                    │   │
│         │            │         │ evaluate rules + cooldown    │   │
│         │            │         ▼                              │   │
│         │            │  RemediationService  (opt-in)          │   │
│         │            │         │ dry-run | live               │   │
│         │            │         ▼                              │   │
│         │            │  IncidentService                       │   │
│         │            │         │ timeline + escalation        │   │
│         │            └─────────┼──────────────────────────────┘   │
│         │                      │                                   │
│         ▼                      ▼                                   │
│  ┌─────────────────────────────────────────────────┐              │
│  │         MongoDB  (localhost:27017/clusterpulse)  │              │
│  │                                                 │              │
│  │  clusters  │ health_snapshots  │  alert_rules   │              │
│  │  alerts    │ incidents                          │              │
│  └─────────────────────────────────────────────────┘              │
└────────────────────────────────────────────────────────────────────┘
          │                                     ▲
          │  admin commands                     │
          ▼                                     │
┌─────────────────────┐             ┌───────────────────────┐
│  Target Cluster A   │             │   Target Cluster B    │
│  MongoDB Replica    │             │   MongoDB Replica     │
│  Set (prod)         │             │   Set (staging)       │
└─────────────────────┘             └───────────────────────┘
```

ClusterPulse itself stores its operational data in a local MongoDB instance. It connects outbound to *target* clusters (the ones being monitored) only during the polling cycle, using short-lived connections with aggressive timeouts.

---

## 4. Component Breakdown

### 4.1 REST Controllers

| Controller | Responsibility |
|---|---|
| `ClusterController` | Register / deregister clusters; retrieve status and health history |
| `AlertController` | Manage alert rules per cluster; list and acknowledge fired alerts |
| `IncidentController` | List, retrieve, and resolve incidents |

Controllers are thin — they delegate all logic to the service layer and return DTOs. Input validation is enforced by `@Valid` annotations on request DTOs, with a `GlobalExceptionHandler` normalizing error responses.

### 4.2 Services

| Service | Responsibility |
|---|---|
| `ClusterRegistryService` | CRUD for registered cluster metadata; duplicate URI detection; status updates |
| `HealthPollerService` | Opens a short-lived `MongoClient` per cluster, runs `replSetGetStatus` + `serverStatus`, parses the response, and saves a `HealthSnapshot`. `MongoClientSettings` are cached per URI to avoid redundant object construction on every poll. |
| `AlertEngineService` | Loads enabled rules per cluster, evaluates each metric value against its threshold and comparator, respects cooldown windows. Fired alerts and rule timestamps are batched into a single `saveAll()` each, reducing MongoDB write round-trips from 2N to 2. |
| `RemediationService` | Filters critical-severity alerts, determines the appropriate action per metric type, and executes (or dry-runs) the action against the target cluster |
| `IncidentService` | Groups alerts into incidents, manages lifecycle transitions (OPEN → INVESTIGATING → MITIGATING → RESOLVED), maintains timeline entries for audit |
| `SnapshotRetentionService` | Runs hourly. Deletes `health_snapshots` older than the configured retention window (default 72 h) for each registered cluster. Isolates failures per-cluster so one bad cleanup does not abort the others. |

### 4.3 Scheduler

`HealthCheckScheduler` runs on a `ThreadPoolTaskScheduler` (4 threads) via Spring's `@Scheduled`. Each tick it submits one `CompletableFuture` per registered cluster to a dedicated `clusterPollExecutor` (core=8, max=16) and awaits all with a 60-second cap. Clusters are therefore polled **in parallel** — a slow or unreachable cluster no longer delays healthy ones. The `fixedDelay` semantics mean the 30-second interval starts *after* the previous cycle finishes, preventing pile-up if aggregate polling time grows.

### 4.4 Configuration

| Class | Responsibility |
|---|---|
| `MongoConfig` | Enables MongoDB auditing for `@CreatedDate` / `@LastModifiedDate` |
| `SchedulerConfig` | Creates a named `ThreadPoolTaskScheduler` with an error handler that logs rather than silences exceptions |

---

## 5. Core Pipeline: Health Check Cycle

Triggered every 30 seconds per the scheduler. Clusters are processed **in parallel** — one `CompletableFuture` per cluster submitted to `clusterPollExecutor`. The full pipeline for a single cluster:

```
HealthCheckScheduler.runHealthCheckCycle()
  │
  └─ CompletableFuture per Cluster (parallel, timeout=60s):
       │
       ├─ 1. POLL
       │     HealthPollerService.pollCluster(cluster)
       │       ├─ getOrBuildSettings(uri)  → ConcurrentHashMap cache, built once per URI
       │       ├─ open MongoClient (5s connect timeout, 10s read timeout)
       │       ├─ run replSetGetStatus   → parse member states, replication lag
       │       ├─ run serverStatus       → parse connections, opcounters
       │       ├─ computeStatus()        → HEALTHY / DEGRADED / CRITICAL
       │       ├─ save HealthSnapshot    → health_snapshots collection
       │       └─ on failure: save UNREACHABLE snapshot (alerts still fire)
       │
       ├─ 2. UPDATE STATUS
       │     ClusterRegistryService.updateClusterHealth(...)
       │       └─ write computed status back to clusters collection
       │
       ├─ 3. EVALUATE ALERTS
       │     AlertEngineService.evaluateRules(clusterId, snapshot)
       │       ├─ early exit if no enabled rules
       │       ├─ for each rule:
       │       │    ├─ skip if in cooldown (lastFiredAt + cooldownMinutes > now)
       │       │    ├─ extract metric value from snapshot
       │       │    ├─ evaluate: actual  <comparator>  threshold
       │       │    └─ if breached → collect rule + build Alert (no save yet)
       │       ├─ ruleRepository.saveAll(firedRules)   ← 1 round-trip
       │       ├─ alertRepository.saveAll(toFire)       ← 1 round-trip
       │       └─ return saved Alert list
       │
       └─ 4. REMEDIATE (if any alerts fired)
             RemediationService.evaluateAndRemediate(cluster, alerts)
               ├─ guard: globalRemediationEnabled AND cluster.remediationEnabled
               ├─ filter to CRITICAL severity only
               ├─ getOrCreateIncident(clusterId, criticalAlerts)
               ├─ for each critical alert:
               │    ├─ determineAction(metricType)
               │    │    REPLICATION_LAG_MS      → TRIGGER_RESYNC
               │    │    CONNECTION_UTILIZATION   → REBALANCE_CONNECTIONS
               │    │    VOTING_MEMBERS_DOWN      → STEPDOWN_PRIMARY
               │    │    others                   → ALERT_ONLY
               │    └─ execute (or dry-run) + log to incident timeline
               └─ save updated incident
```

### Status Computation Logic

The poller computes a cluster status directly from raw metrics, without needing alert rules:

| Condition | Status |
|---|---|
| `votingMembersUp <= totalVotingMembers / 2` | `CRITICAL` (quorum lost) |
| `replicationLag > 30s` OR `connectionUtilization > 90%` | `CRITICAL` |
| `replicationLag > 10s` OR `connectionUtilization > 75%` OR any member down | `DEGRADED` |
| All else | `HEALTHY` |
| Any exception during polling | `UNREACHABLE` |

---

## 6. Data Model

### 6.1 Cluster

Represents a registered MongoDB replica set. Stores connection metadata, current operational status, and whether auto-remediation is permitted.

```
Cluster {
  id                 String          PK (MongoDB ObjectId)
  displayName        String
  connectionUri      String          unique (duplicate check on registration)
  environment        String          PRODUCTION | STAGING | DEVELOPMENT
  status             ClusterStatus   HEALTHY | DEGRADED | CRITICAL | UNREACHABLE | UNKNOWN
  replicaSetName     String
  memberCount        int
  primaryHost        String
  mongoVersion       String
  remediationEnabled boolean         default: false
  tags               Map<String,String>
  registeredAt       Instant         @CreatedDate
  lastUpdatedAt      Instant         @LastModifiedDate
  lastHealthCheckAt  Instant
}
```

### 6.2 HealthSnapshot

Point-in-time metrics captured from one poll cycle. The primary time-series record.

```
HealthSnapshot {
  id                     String   PK
  clusterId              String   FK → Cluster
  capturedAt             Instant
  maxReplicationLagMs    long
  oplogWindowHours       long
  votingMembersUp        int
  totalVotingMembers     int
  currentConnections     int
  availableConnections   int
  connectionUtilizationPct double
  dataStorageMb          long
  indexStorageMb         long
  freeStorageMb          long
  storageUtilizationPct  double
  opsPerSecond           long
  queryTimeAvgMs         long
  slowQueryCount         long
  members                List<MemberHealth>
  computedStatus         ClusterStatus

  MemberHealth {
    host             String
    state            String    PRIMARY | SECONDARY | ARBITER | ...
    healthy          boolean
    replicationLagMs long
    uptimeSeconds    long
    lastHeartbeat    Instant
  }
}
```

Compound index on `{clusterId: 1, capturedAt: -1}` optimizes both "latest snapshot" and "snapshot history in time range" queries.

### 6.3 AlertRule

A configurable threshold bound to one cluster. Supports 7 metric types and 3 comparators.

```
AlertRule {
  id              String
  clusterId       String      FK → Cluster
  ruleName        String
  metricType      MetricType  REPLICATION_LAG_MS | CONNECTION_UTILIZATION_PCT |
                              STORAGE_UTILIZATION_PCT | OPLOG_WINDOW_HOURS |
                              SLOW_QUERY_COUNT | OPS_PER_SECOND | VOTING_MEMBERS_DOWN
  comparator      Comparator  GREATER_THAN | LESS_THAN | EQUALS
  threshold       double
  severity        Severity    INFO | WARNING | CRITICAL
  enabled         boolean     default: true
  cooldownMinutes int         default: 5
  createdAt       Instant     @CreatedDate
  lastFiredAt     Instant     null until first fire
}
```

### 6.4 Alert

A fired signal — the result of a rule breach on one snapshot. Alerts are ephemeral; they may be escalated into an Incident.

```
Alert {
  id              String
  clusterId       String
  ruleId          String       FK → AlertRule
  ruleName        String       denormalized for query convenience
  severity        Severity
  metricType      MetricType
  actualValue     double
  thresholdValue  double
  message         String
  status          AlertStatus  OPEN | ACKNOWLEDGED | RESOLVED | ESCALATED
  firedAt         Instant
  acknowledgedAt  Instant
  acknowledgedBy  String
  incidentId      String       set when escalated to an Incident
}
```

Compound index on `{clusterId: 1, firedAt: -1}`.

### 6.5 Incident

A durable operational record. Groups multiple alerts from the same cluster into one trackable event with a full audit timeline.

```
Incident {
  id              String
  clusterId       String
  title           String
  description     String
  severity        Severity
  status          IncidentStatus  OPEN | INVESTIGATING | MITIGATING | RESOLVED | POST_MORTEM
  alertIds        List<String>
  timeline        List<TimelineEntry>
  openedAt        Instant
  resolvedAt      Instant
  resolvedBy      String
  resolution      String
  durationSeconds long            computed on resolution

  TimelineEntry {
    timestamp  Instant
    action     String
    actor      String   "system" | "auto-remediation" | <username>
    details    String
  }
}
```

---

## 7. Alert Engine Design

### Evaluation Flow

For each enabled `AlertRule` on a cluster, the engine:

1. **Cooldown check** — if `now - rule.lastFiredAt < cooldownMinutes`, skip. This prevents alert storms when a metric stays above threshold across many consecutive poll cycles.
2. **Metric extraction** — reads the relevant field from the `HealthSnapshot` via a switch on `MetricType`.
3. **Threshold evaluation** — `GREATER_THAN`, `LESS_THAN`, or `EQUALS` (with epsilon `0.001` for float equality).
4. **Fire** — if breached, persist an `Alert` and stamp `rule.lastFiredAt = now`.

### Cooldown Semantics

```
rule.lastFiredAt = T0

poll at T0+2min  → T0+2 < T0+5  → suppressed
poll at T0+4min  → T0+4 < T0+5  → suppressed
poll at T0+6min  → T0+6 > T0+5  → fires again, lastFiredAt = T0+6
```

The cooldown is per-rule, not per-cluster, so different rules on the same cluster can fire independently.

### Alert Lifecycle

```
OPEN → ACKNOWLEDGED (manual via API)
OPEN → ESCALATED    (by RemediationService when attached to an Incident)
OPEN / ACKNOWLEDGED → RESOLVED
```

---

## 8. Remediation Engine Design

Remediation is **opt-in at two levels**: the global `clusterpulse.remediation.enabled` flag must be `true`, and the individual cluster must have `remediationEnabled = true`. This double-gate prevents accidental live actions.

### Dry-Run Mode

When `clusterpulse.remediation.dry-run = true` (the default), the engine logs the action it *would* take and records a `DRY_RUN_REMEDIATION` timeline entry on the incident — but does not connect to the target cluster. This lets operators verify the remediation logic before enabling live execution.

### Action Mapping

| Metric Type | Action | Mechanism |
|---|---|---|
| `REPLICATION_LAG_MS` | `TRIGGER_RESYNC` | `replSetResync` admin command on lagging secondary |
| `CONNECTION_UTILIZATION_PCT` | `REBALANCE_CONNECTIONS` | Graceful connection drain and redirect |
| `VOTING_MEMBERS_DOWN` | `STEPDOWN_PRIMARY` | `replSetStepDown` to force a new election |
| `STORAGE_UTILIZATION_PCT` | `ALERT_ONLY` | No automated action — human intervention required |
| All others | `ALERT_ONLY` | — |

Only `CRITICAL` severity alerts trigger remediation. `INFO` and `WARNING` alerts are visible in the API but never trigger automated actions.

---

## 9. API Design

All endpoints are under `/api`. The service exposes OpenAPI docs at `/api-docs` and Swagger UI at `/swagger-ui.html`.

### Cluster Management

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/clusters` | Register a new cluster |
| `GET` | `/api/clusters` | List all clusters with latest status |
| `GET` | `/api/clusters/{id}` | Get cluster detail + health summary |
| `DELETE` | `/api/clusters/{id}` | Deregister cluster |
| `GET` | `/api/clusters/{id}/health` | Latest health snapshot |
| `GET` | `/api/clusters/{id}/health/history` | Historical snapshots (`?hours=24&limit=100`) |

### Alerting

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/clusters/{clusterId}/rules` | Create alert rule for a cluster |
| `GET` | `/api/clusters/{clusterId}/rules` | List all rules for a cluster |
| `GET` | `/api/alerts` | List all fired alerts |
| `PATCH` | `/api/alerts/{id}/acknowledge` | Acknowledge an alert |

### Incidents

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/incidents` | List all incidents |
| `GET` | `/api/incidents/{id}` | Get incident with full timeline |
| `PATCH` | `/api/incidents/{id}/resolve` | Resolve an incident with a resolution note |

### Error Response Format

All errors are returned as:

```json
{
  "timestamp": "2026-02-03T09:45:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Cluster not found: abc123"
}
```

Handled errors: `ClusterNotFoundException (404)`, `ClusterUnreachableException (503)`, `IllegalArgumentException (400)`, bean validation errors `(400)`, uncaught exceptions `(500)`.

---

## 10. Storage Design

### Collections

| Collection | Contents | Compound Index |
|---|---|---|
| `clusters` | Registered cluster metadata | — |
| `health_snapshots` | Time-series health readings | `{clusterId: 1, capturedAt: -1}` |
| `alert_rules` | Configurable alert thresholds | `{clusterId: 1}` |
| `alerts` | Fired alert records | `{clusterId: 1, firedAt: -1}` |
| `incidents` | Incident lifecycle records | `{clusterId: 1}` |

`spring.data.mongodb.auto-index-creation: true` ensures indexes are applied at startup.

### Snapshot Retention

`SnapshotRetentionService` runs hourly (configurable via `clusterpulse.health.retention-cleanup-interval-ms`) and calls `HealthSnapshotRepository.deleteByClusterIdAndCapturedAtBefore()` for each registered cluster with a cutoff of `now - retentionHours`. Default retention is 72 hours. Failures on individual clusters are caught and logged — the cleanup loop continues for remaining clusters.

### Query Patterns

| Operation | Query | Index used |
|---|---|---|
| Latest snapshot for cluster | `findFirstByClusterIdOrderByCapturedAtDesc()` | `{clusterId, capturedAt}` |
| Snapshots in time window | `findByClusterIdAndCapturedAtBetween(...)` | `{clusterId, capturedAt}` |
| Open incidents for cluster | `findFirstByClusterIdAndStatusOrderByOpenedAtDesc()` | `{clusterId}` |
| Alerts since last fire (cooldown) | `findByClusterIdAndStatus()` | `{clusterId}` |

---

## 11. Threading and Concurrency

### Thread Pools

Two separate pools keep scheduling and polling concerns isolated:

| Pool | Bean | Size | Thread prefix | Purpose |
|---|---|---|---|---|
| Scheduler | `ThreadPoolTaskScheduler` | 4 | `health-poller-` | Drives `@Scheduled` ticks for `HealthCheckScheduler` and `SnapshotRetentionService` |
| Poll executor | `ThreadPoolTaskExecutor` (`clusterPollExecutor`) | core=8, max=16, queue=50 | `cluster-poll-` | Executes `processCluster()` calls in parallel within each health check cycle |

### Parallel Cluster Polling

`runHealthCheckCycle()` fans out one `CompletableFuture.runAsync()` per registered cluster to `clusterPollExecutor`, then blocks with `allOf(...).get(60, TimeUnit.SECONDS)` before the next `fixedDelay` tick can start.

This reduces total cycle time from `O(n × maxPollTime)` to `O(maxPollTime)` — the slowest single cluster now determines cycle duration rather than the sum of all cluster durations.

### Per-Cluster Fault Isolation

Each `CompletableFuture` wraps `processCluster()` in a try-catch, so an exception in one cluster's task does not cancel the others. The 5-second connect timeout and 10-second read timeout on each `MongoClient` bound the worst-case contribution of any single cluster to the 60-second overall cap.

### Thread Safety

All service beans are Spring singletons. Shared mutable state:
- `AlertRule.lastFiredAt` — set inside `evaluateRules()` before the `saveAll()` batch flush. Two clusters could theoretically share a rule (different clusters can have rules with the same ID via copy), but in practice rules are cluster-scoped. No optimistic locking is added currently.
- `HealthPollerService.settingsCache` — `ConcurrentHashMap` with `computeIfAbsent`, safe for concurrent reads from multiple `cluster-poll-*` threads.
- MongoDB writes — safe; all writes go through `MongoTemplate` which is thread-safe.

---

## 12. Failure Modes and Resilience

### Target Cluster Unreachable

If a poll fails for any reason (network timeout, auth failure, wrong URI), the poller catches the exception and saves an `UNREACHABLE` snapshot. This means:
- The cluster status is immediately updated to `UNREACHABLE` in the registry
- Alert rules referencing metrics like `VOTING_MEMBERS_DOWN` can still fire on the snapshot (with default/zero values)
- Because polling is now parallel, one unreachable cluster does not delay healthy ones in the same cycle

### ClusterPulse's Own MongoDB Unavailable

If the local MongoDB is down, Spring Data repository calls throw exceptions. These propagate to the scheduler's error handler (configured in `SchedulerConfig`), which logs the error. The scheduler itself does not crash — the next tick will retry.

### Alert Storms

Cooldown windows (`cooldownMinutes` per rule, default 5 minutes) prevent the same rule from firing on every poll cycle during a prolonged degradation event.

### Duplicate Registration

`ClusterRegistryService` checks `ClusterRepository.existsByConnectionUri()` before saving a new cluster and throws `IllegalArgumentException` if the URI is already registered.

---

## 13. Configuration Reference

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/clusterpulse  # ClusterPulse's own storage
      auto-index-creation: true

server:
  port: 8080

clusterpulse:
  polling:
    interval-seconds: 30      # Gap between health check cycles (fixedDelay)
    initial-delay-seconds: 10 # Delay before first cycle on startup
  alerts:
    evaluation-interval-seconds: 15  # (informational; evaluation runs inside the poll cycle)
  remediation:
    enabled: false    # Master switch — set true to enable automated actions globally
    dry-run: true     # When true, log actions without executing; safe default
  health:
    snapshot-retention-hours: 72  # Retention window for health_snapshots
```

---

## 14. Scalability Considerations

### Current Design (Single Instance)

ClusterPulse runs as a single Spring Boot process. Polling is parallel within each cycle, so it scales to tens of clusters comfortably within the default 30-second interval.

### Remaining Horizontal Scaling Bottlenecks

If the number of clusters grows to hundreds:

1. **Single scheduler** — with multiple ClusterPulse instances, each would poll all clusters, causing duplicate snapshots and double-firing alerts. A distributed lock (e.g., via MongoDB's `findAndModify` as a mutex, or Redis `SETNX`) keyed on cluster ID would allow work-sharding across instances.
2. **`clusterPollExecutor` saturation** — the pool cap of 16 threads means > 16 simultaneous clusters will queue. Increasing `maxPoolSize` or sharding clusters across instances addresses this.
3. **`health_snapshots` growth** — at 30-second intervals, one cluster produces ~2,880 snapshots/day. With 72-hour retention and 100 clusters that is ~864,000 documents. `SnapshotRetentionService` handles cleanup, but a MongoDB TTL index on `capturedAt` would be a more efficient alternative (server-side expiry vs. application-driven deletes).

### Stateless API Layer

The REST controllers hold no in-memory state. Scaling the API layer horizontally (multiple instances behind a load balancer) requires only that the scheduler not run on API-only nodes — a simple profile separation (`@Profile("scheduler")` on `HealthCheckScheduler` and `SnapshotRetentionService`) would achieve this.

---

## 15. Performance Optimizations (Dec 2025 – Feb 2026)

This section summarises the targeted improvements made after the initial feature-complete build.

### 15.1 Parallel Cluster Polling (Dec 2025)

**Problem:** `HealthCheckScheduler` iterated clusters in a for-loop. A cluster with a slow DNS or firewall timeout blocked every subsequent cluster in the same cycle. With 10 clusters at 5-second connect timeout each, worst case was a 50-second cycle.

**Solution:** Each cluster's `processCluster()` is submitted as a `CompletableFuture.runAsync()` to a dedicated `clusterPollExecutor` bean (`ThreadPoolTaskExecutor`, core=8, max=16). `CompletableFuture.allOf()` waits up to 60 seconds for all tasks.

**Effect:** Cycle time drops from `O(n × maxPollTime)` to `O(maxPollTime)`. 20 clusters at 500 ms average poll time now complete in ~500 ms instead of ~10 seconds.

---

### 15.2 Snapshot Retention Enforcement (Dec 2025)

**Problem:** `HealthSnapshotRepository.deleteByClusterIdAndCapturedAtBefore()` was defined but never called. The `health_snapshots` collection grew without bound, degrading query performance over time.

**Solution:** `SnapshotRetentionService` runs on an hourly `@Scheduled` tick. For each registered cluster it deletes snapshots whose `capturedAt` is before `now - retentionHours`. Failures are caught per-cluster so a single bad cluster does not abort the full cleanup run.

**Effect:** Collection size is bounded to `nClusters × snapshotsPerHour × retentionHours`. At default settings (30s interval, 72h retention): 120 snapshots/hour × 72h = 8,640 snapshots per cluster maximum.

---

### 15.3 MongoClientSettings Cache (Dec 2025)

**Problem:** `HealthPollerService` rebuilt a `MongoClientSettings` object on every poll tick. This involved parsing the connection string and constructing immutable config objects — unnecessary work given that the URI never changes between polls.

**Solution:** A `ConcurrentHashMap<String, MongoClientSettings>` keyed by connection URI. `computeIfAbsent` ensures the settings are built exactly once per unique URI, with no locking overhead on the hot read path (concurrent polls of different clusters).

**Effect:** Eliminates `n` redundant `ConnectionString` parses per cycle. Thread-safe with no contention given `ConcurrentHashMap` semantics.

---

### 15.4 Batch Alert and Rule Persistence (Jan 2026)

**Problem:** When `k` alert rules fired in a single cycle, `AlertEngineService` issued `k` individual `ruleRepository.save()` calls followed by `k` individual `alertRepository.save()` calls — `2k` synchronous MongoDB round-trips inside a hot parallel task.

**Solution:** The evaluation loop now collects fired rules and built `Alert` objects into two local lists. After the loop, `ruleRepository.saveAll(firedRules)` and `alertRepository.saveAll(toFire)` flush both in exactly 2 round-trips regardless of `k`. An early-exit guard returns immediately when a cluster has no enabled rules.

**Effect:** Reduces per-cycle MongoDB writes from `O(k)` to `O(1)`. For a cluster with 10 firing rules, this cuts 20 round-trips down to 2. Also removes the `fireAlert()` private method, inlining the alert construction for clarity.
