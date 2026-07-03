# Music Streaming Analytics

A distributed, event-driven analytics platform for real-time music play tracking.
Built to explore stream processing, distributed state management, and cloud-native deployment patterns.

## Architecture

![Architecture](music_streaming_architecture_v2.svg)

Three microservices communicating via Kafka:

- **ingestion-service**: HTTP API for clients to report play events, publishes to Kafka
- **stream-processor**: Kafka Streams application, computes windowed aggregations (completion rate, top songs)
- **query-service**: Consumes aggregation results, serves query API backed by Redis

## Tech Stack

- **Language**: Kotlin
- **Framework**: Spring Boot 3.5
- **Stream Processing**: Kafka Streams
- **Storage**: PostgreSQL, Redis
- **Deployment**: Docker, Kubernetes (minikube for dev, GKE planned)
- **CI**: GitHub Actions

## Key Design Decisions

See [docs/decisions/](docs/decisions/) for ADRs on:
- Kafka internal topic replication under single-broker setup
- Advertised listeners and local dev loop tradeoffs
- Completion rate business definition (90% threshold)
- CQRS-style query architecture (Kafka → Redis, decoupled from stream-processor state)

## Quick Start

Prerequisites: Docker, JDK 21

```bash
# Start Kafka, PostgreSQL, Redis
docker compose -f docker-compose.dev.yml up -d

# Run each service (in separate terminals, from project root)
./gradlew :ingestion-service:bootRun
./gradlew :stream-processor:bootRun
./gradlew :query-service:bootRun

# Send test events
./scripts/test-data.sh

# Query results
curl http://localhost:8082/api/songs/song-A/completion
curl "http://localhost:8082/api/songs/top?n=5"
```

## Testing Late-arriving Events

```bash
./scripts/test-late.sh
```

Verifies that events within the grace period are correctly aggregated
into the original window, while events beyond it are dropped.

## Ports

| Service | Port |
|---|---|
| ingestion-service | 8080 |
| stream-processor | 8081 |
| query-service | 8082 |
| PostgreSQL | 5432 |
| Redis | 6379 |
| Kafka | 9092 |

## Project Status

- [x] Week 1: Core infrastructure and end-to-end pipeline
- [ ] Week 2: Observability, GKE deployment, load testing
