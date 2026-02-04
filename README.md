# ClusterPulse — MongoDB Cluster Health Monitor & Auto-Remediation Engine

> A backend platform for monitoring MongoDB replica sets, detecting anomalies, and executing automated healing actions — inspired by MongoDB Atlas's cluster management architecture.

---

## Problem Statement

Managing MongoDB clusters at scale requires continuous health monitoring, proactive anomaly detection, and automated remediation to maintain high availability. Without these, operators face silent replication lag, undetected failover storms, and manual toil during incidents.

**ClusterPulse** solves this by providing:

- **Real-time health monitoring** of registered MongoDB replica sets (replication lag, oplog window, connection saturation, storage utilization)
- **Configurable alerting rules** with threshold-based and trend-based anomaly detection
- **Automated remediation actions** (step-down unhealthy primaries, trigger resync on lagging secondaries, connection pool rebalancing)
- **Incident lifecycle management** with audit trails and post-mortem support
- **Multi-cluster management** via a RESTful API, simulating what Atlas does under the hood

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   ClusterPulse API                  │
│              (Spring Boot + REST API)               │
├─────────┬──────────┬────────────┬───────────────────┤
│ Cluster │  Health  │  Alert     │  Remediation      │
│ Registry│  Poller  │  Engine    │  Engine            │
│ Service │  Service │  Service   │  Service           │
├─────────┴──────────┴────────────┴───────────────────┤
│              MongoDB (Metadata Store)               │
│   clusters | health_snapshots | alerts | incidents  │
├─────────────────────────────────────────────────────┤
│          Monitored MongoDB Replica Sets             │
│     (target clusters registered via API)            │
└─────────────────────────────────────────────────────┘
```

---

## Tech Stack

| Layer          | Technology                          |
|----------------|-------------------------------------|
| Language       | Java 17                             |
| Framework      | Spring Boot 3.2                     |
| Database       | MongoDB 7.x (Spring Data MongoDB)   |
| Scheduling     | Spring Scheduler (cron-based polls)  |
| Build          | Maven                               |
| Testing        | JUnit 5, Mockito                    |
| API Docs       | Springdoc OpenAPI (Swagger)         |

---

## Project Structure

```
clusterpulse/
├── src/main/java/com/clusterpulse/
│   ├── ClusterPulseApplication.java       # Entry point
│   ├── config/
│   │   ├── MongoConfig.java               # MongoDB connection config
│   │   └── SchedulerConfig.java           # Scheduler thread pool config
│   ├── model/
│   │   ├── Cluster.java                   # Registered cluster metadata
│   │   ├── HealthSnapshot.java            # Point-in-time health reading
│   │   ├── AlertRule.java                 # Configurable alert thresholds
│   │   ├── Alert.java                     # Fired alert record
│   │   └── Incident.java                  # Incident lifecycle record
│   ├── dto/
│   │   ├── ClusterRegistrationRequest.java
│   │   ├── ClusterStatusResponse.java
│   │   ├── AlertRuleRequest.java
│   │   └── IncidentResponse.java
│   ├── repository/
│   │   ├── ClusterRepository.java
│   │   ├── HealthSnapshotRepository.java
│   │   ├── AlertRuleRepository.java
│   │   ├── AlertRepository.java
│   │   └── IncidentRepository.java
│   ├── service/
│   │   ├── ClusterRegistryService.java    # CRUD for monitored clusters
│   │   ├── HealthPollerService.java       # Polls rs.status(), serverStatus
│   │   ├── AlertEngineService.java        # Evaluates rules against snapshots
│   │   ├── RemediationService.java        # Executes auto-heal actions
│   │   └── IncidentService.java           # Incident lifecycle management
│   ├── scheduler/
│   │   └── HealthCheckScheduler.java      # Scheduled polling orchestrator
│   ├── controller/
│   │   ├── ClusterController.java         # /api/clusters endpoints
│   │   ├── AlertController.java           # /api/alerts endpoints
│   │   └── IncidentController.java        # /api/incidents endpoints
│   └── exception/
│       ├── ClusterNotFoundException.java
│       ├── ClusterUnreachableException.java
│       └── GlobalExceptionHandler.java
├── src/main/resources/
│   └── application.yml                    # App configuration
├── src/test/java/com/clusterpulse/
│   ├── service/
│   │   ├── ClusterRegistryServiceTest.java
│   │   └── AlertEngineServiceTest.java
│   └── controller/
│       └── ClusterControllerTest.java
├── docs/
│   └── API_REFERENCE.md                   # Full API documentation
├── pom.xml
└── README.md
```

---

## API Endpoints

### Cluster Management
| Method | Endpoint                        | Description                     |
|--------|---------------------------------|---------------------------------|
| POST   | `/api/clusters`                 | Register a new cluster          |
| GET    | `/api/clusters`                 | List all registered clusters    |
| GET    | `/api/clusters/{id}`            | Get cluster details + status    |
| DELETE | `/api/clusters/{id}`            | Deregister a cluster            |
| GET    | `/api/clusters/{id}/health`     | Latest health snapshot          |
| GET    | `/api/clusters/{id}/health/history` | Historical health data     |

### Alert Rules & Alerts
| Method | Endpoint                        | Description                     |
|--------|---------------------------------|---------------------------------|
| POST   | `/api/clusters/{id}/rules`      | Create alert rule for cluster   |
| GET    | `/api/clusters/{id}/rules`      | List alert rules                |
| GET    | `/api/alerts`                   | List all fired alerts           |
| PATCH  | `/api/alerts/{id}/acknowledge`  | Acknowledge an alert            |

### Incidents
| Method | Endpoint                        | Description                     |
|--------|---------------------------------|---------------------------------|
| GET    | `/api/incidents`                | List all incidents              |
| GET    | `/api/incidents/{id}`           | Get incident details + timeline |
| PATCH  | `/api/incidents/{id}/resolve`   | Resolve an incident             |

---

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.8+
- MongoDB 6.x / 7.x running locally or via Atlas

### Run
```bash
# Clone and build
mvn clean install

# Start the application
mvn spring-boot:run

# API docs available at
http://localhost:8080/swagger-ui.html
```

### Configuration
Edit `src/main/resources/application.yml` to configure:
- MongoDB connection URI for the metadata store
- Default polling intervals
- Alert evaluation frequency
- Remediation action toggles

---

## Design Decisions

1. **MongoDB as both metadata store and monitoring target** — dogfooding the technology, demonstrating deep familiarity with MongoDB's operational characteristics.

2. **Scheduled polling over change streams** — mirrors how real infrastructure monitoring works at the cluster management layer; change streams are used at the application layer.

3. **Configurable alert rules per cluster** — different clusters have different SLAs; a dev cluster tolerates higher replication lag than a production cluster.

4. **Remediation as a separate service** — clean separation of concerns; detection and action are decoupled, making the system testable and auditable.

5. **Incident lifecycle** — alerts are ephemeral signals, incidents are the durable operational record. This maps to real-world SRE practices.

---

## License

MIT
