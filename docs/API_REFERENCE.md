# ClusterPulse API Reference

Base URL: `http://localhost:8080`

Interactive docs: `http://localhost:8080/swagger-ui.html`

---

## Clusters

### Register a Cluster

```
POST /api/clusters
Content-Type: application/json

{
  "displayName": "Production RS-1",
  "connectionUri": "mongodb://user:pass@host1:27017,host2:27017/?replicaSet=rs0",
  "environment": "PRODUCTION",
  "remediationEnabled": false,
  "tags": {
    "team": "platform",
    "region": "us-east-1"
  }
}

Response: 201 Created
{
  "id": "665a1b...",
  "displayName": "Production RS-1",
  "status": "UNKNOWN",
  ...
}
```

### List All Clusters

```
GET /api/clusters

Response: 200 OK
[
  {
    "id": "665a1b...",
    "displayName": "Production RS-1",
    "environment": "PRODUCTION",
    "status": "HEALTHY",
    "latestHealth": {
      "maxReplicationLagMs": 120,
      "connectionUtilizationPct": 32.5,
      "votingMembersUp": 3,
      "totalVotingMembers": 3
    }
  }
]
```

### Get Cluster Details

```
GET /api/clusters/{id}

Response: 200 OK
```

### Deregister a Cluster

```
DELETE /api/clusters/{id}

Response: 204 No Content
```

### Get Latest Health Snapshot

```
GET /api/clusters/{id}/health

Response: 200 OK
{
  "clusterId": "665a1b...",
  "capturedAt": "2026-03-26T10:30:00Z",
  "maxReplicationLagMs": 120,
  "connectionUtilizationPct": 32.5,
  "storageUtilizationPct": 58.0,
  "members": [
    {
      "host": "host1:27017",
      "state": "PRIMARY",
      "healthy": true,
      "replicationLagMs": 0
    },
    {
      "host": "host2:27017",
      "state": "SECONDARY",
      "healthy": true,
      "replicationLagMs": 120
    }
  ],
  "computedStatus": "HEALTHY"
}
```

### Get Health History

```
GET /api/clusters/{id}/health/history?hours=24&limit=50

Response: 200 OK
[ ...array of HealthSnapshot objects... ]
```

---

## Alert Rules & Alerts

### Create an Alert Rule

```
POST /api/clusters/{clusterId}/rules
Content-Type: application/json

{
  "ruleName": "High Replication Lag",
  "metricType": "REPLICATION_LAG_MS",
  "comparator": "GREATER_THAN",
  "threshold": 10000,
  "severity": "WARNING",
  "cooldownMinutes": 5
}

Response: 201 Created
```

**Available metric types:**
`REPLICATION_LAG_MS`, `CONNECTION_UTILIZATION_PCT`, `STORAGE_UTILIZATION_PCT`,
`OPLOG_WINDOW_HOURS`, `SLOW_QUERY_COUNT`, `OPS_PER_SECOND`, `VOTING_MEMBERS_DOWN`

**Comparators:** `GREATER_THAN`, `LESS_THAN`, `EQUALS`

**Severities:** `INFO`, `WARNING`, `CRITICAL`

### List Alert Rules for a Cluster

```
GET /api/clusters/{clusterId}/rules

Response: 200 OK
```

### List All Fired Alerts

```
GET /api/alerts

Response: 200 OK
```

### Acknowledge an Alert

```
PATCH /api/alerts/{id}/acknowledge
Content-Type: application/json

{
  "acknowledgedBy": "jaspreet"
}

Response: 200 OK
```

---

## Incidents

### List All Incidents

```
GET /api/incidents

Response: 200 OK
```

### Get Incident Details

```
GET /api/incidents/{id}

Response: 200 OK
{
  "id": "665b2c...",
  "clusterId": "665a1b...",
  "title": "Cluster health degradation — 2 alert(s)",
  "severity": "CRITICAL",
  "status": "OPEN",
  "alertIds": ["alert-1", "alert-2"],
  "timeline": [
    {
      "timestamp": "2026-03-26T10:35:00Z",
      "action": "INCIDENT_OPENED",
      "actor": "system",
      "details": "Auto-created from 2 alert(s)"
    },
    {
      "timestamp": "2026-03-26T10:35:01Z",
      "action": "DRY_RUN_REMEDIATION",
      "actor": "auto-remediation",
      "details": "[DRY-RUN] Remediation [TRIGGER_RESYNC] for metric REPLICATION_LAG_MS"
    }
  ],
  "openedAt": "2026-03-26T10:35:00Z",
  "durationSeconds": 0
}
```

### Resolve an Incident

```
PATCH /api/incidents/{id}/resolve
Content-Type: application/json

{
  "resolvedBy": "jaspreet",
  "resolution": "Replication lag stabilized after secondary resync completed"
}

Response: 200 OK
```

---

## Status Codes

| Code | Meaning                                      |
|------|----------------------------------------------|
| 200  | Success                                      |
| 201  | Resource created                             |
| 204  | No content (successful deletion)             |
| 400  | Bad request / validation error               |
| 404  | Cluster or resource not found                |
| 503  | Target cluster unreachable                   |
| 500  | Internal server error                        |
