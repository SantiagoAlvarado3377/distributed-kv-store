# Distributed Key-Value Store

Fault-tolerant distributed key-value store with consistent hashing, replication, and write-ahead logging. Built in Java 17 + Spring Boot 3 + Docker Compose.

---

## Architecture Diagram

```
Client (curl / kv-client CLI)
         │
         ▼
  ┌──────────────┐
  │   node-1     │  ◄── Primary / Coordinator (port 8081)
  │  (primary)   │
  └──────┬───────┘
         │  replicate to next 2 nodes on ring
         ▼
  Hash Ring (5 physical × 5 virtual = 25 virtual nodes)

  Positions on ring (clockwise):

  0 ──────────────────────────────────────────────── 2^32-1
  │                                                      │
  │  vnode-1#0  vnode-3#2  vnode-2#1  vnode-5#3  ...   │
  │    ↓           ↓           ↓          ↓             │
  │  node-1      node-3      node-2     node-5           │
  └──────────────────────────────────────────────────────┘

  Replication Flow (factor = 3)
  ┌─────────┐    PUT /keys/foo      ┌─────────┐
  │ Client  │ ─────────────────────▶│ node-1  │ (primary)
  └─────────┘                       └────┬────┘
                                         │ /internal/replicate/foo
                                    ┌────▼────┐   ┌─────────┐
                                    │ node-2  │   │ node-3  │  (next 2 on ring)
                                    └─────────┘   └─────────┘

  Nodes: node-1 (8081) ─ node-2 (8082) ─ node-3 (8083) ─ node-4 (8084) ─ node-5 (8085)
```

---

## Design Decisions

### Consistent Hashing
- Each physical node is represented by **5 virtual nodes** on a 2³²-size ring using MD5 hashes.
- Virtual nodes improve key distribution: without them, uneven placement causes hot-spots.
- When a key arrives, it is routed clockwise to the nearest virtual node; the owning physical node handles the request.
- The ring is held in memory on every node (all nodes know about all peers via `PEER_NODES`).

### Configuration-based Leader vs. Raft
- **node-1 is always the primary** (set via `PRIMARY_HOST=node-1`).
- Tradeoff: simple and sufficient for a demo, but not fault-tolerant — if node-1 crashes, writes fail with HTTP 503.
- A real production system would use Raft or Multi-Paxos for leader election, at the cost of significant complexity (log compaction, split-brain prevention, term management).

### WAL vs. Snapshotting
- **WAL** provides operation-level durability. Every PUT/DELETE is fsynced to `wal.log` before being applied in memory.
- **Snapshots** (`snapshot.json`) prevent the WAL from growing unboundedly. After a snapshot is written, log entries older than the snapshot timestamp are discarded (WAL compaction).
- On startup: load snapshot → replay WAL → node is back to its last-known state.
- Tradeoff: a crash between a snapshot and WAL compaction is safe (WAL entries are replayed on top of the snapshot).

### In-memory Store
- `ConcurrentHashMap` provides thread-safe reads and writes without a global lock.
- Snapshots are written atomically (write to `.tmp` then rename) to avoid corrupt state on crash.

---

## Failure Scenarios

| Scenario | Behaviour |
|---|---|
| **node-1 (primary) goes down** | All write requests to any node fail with `HTTP 503 – Primary node is unreachable`. Reads still succeed on surviving replicas. |
| **2 replica nodes go down** | Writes succeed on the primary. Replication warnings are logged for unreachable nodes but writes are not blocked. With replication factor 3, only 1 replica copy is guaranteed when 2 are down. |
| **All replicas for a key go down** | Reads fail with `HTTP 404` (key not found on this node). Writes can still reach the primary, but replication silently fails. Recovery requires the failed nodes to restart and catch up via WAL replay from their last snapshot. |
| **Simulated failure (admin)** | The node returns `HTTP 503` for all key requests but continues responding to `/health` and `/admin/recover`. |

---

## How to Run

### Prerequisites
- Docker ≥ 24 and Docker Compose v2
- (Optional) Java 17 + Maven 3.9 for local builds

### Start the cluster
```bash
cd docker
docker compose up --build
```

All five nodes will start. Verify health:
```bash
curl http://localhost:8081/health
curl http://localhost:8082/health
```

### Example curl commands

```bash
# PUT a key
curl -X PUT http://localhost:8081/keys/greeting \
     -H 'Content-Type: application/json' \
     -d '{"value": "hello world"}'

# GET the key (read from any replica)
curl http://localhost:8081/keys/greeting
curl http://localhost:8083/keys/greeting

# DELETE the key
curl -X DELETE http://localhost:8081/keys/greeting

# Health check
curl http://localhost:8081/health
```

### Simulate a node failure

```bash
# Make node-3 stop responding to key requests
curl -X POST http://localhost:8083/admin/simulate-failure

# Confirm it is in failure mode
curl http://localhost:8083/health
# → {"nodeId":"node-3","status":"simulated-failure",...}

# Reads on node-3 now return 503
curl http://localhost:8083/keys/greeting

# Recover node-3
curl -X POST http://localhost:8083/admin/recover
```

### Using the CLI client

```bash
# Build
cd ..
mvn package -pl client -am -DskipTests

# Run
java -jar client/target/client-1.0.0.jar http://localhost:8081 put city "Seattle"
java -jar client/target/client-1.0.0.jar http://localhost:8081 get city
java -jar client/target/client-1.0.0.jar http://localhost:8081 delete city
java -jar client/target/client-1.0.0.jar http://localhost:8081 health
```

---

## Project Structure

```
distributed-kv-store/
├── pom.xml                          # Root multi-module Maven POM
├── server/                          # Spring Boot node application
│   ├── pom.xml
│   └── src/main/java/com/kvstore/server/
│       ├── ServerApplication.java
│       ├── config/NodeConfig.java   # Env-var driven node configuration
│       ├── controller/
│       │   ├── KeyValueController.java          # PUT/GET/DELETE /keys/{key}
│       │   ├── HealthController.java            # GET /health
│       │   ├── AdminController.java             # POST /admin/simulate-failure|recover
│       │   └── InternalReplicationController.java # /internal/replicate/*
│       ├── service/
│       │   ├── KeyValueService.java     # Core logic + failure simulation
│       │   ├── ReplicationService.java  # Consistent ring init + replication
│       │   └── SnapshotService.java     # Scheduled snapshot writer
│       ├── storage/
│       │   ├── InMemoryStore.java   # ConcurrentHashMap + WAL + snapshot
│       │   └── WriteAheadLog.java   # Append-only log with compaction
│       └── hashing/
│           └── ConsistentHashRing.java  # Virtual-node ring
├── client/                          # CLI client
│   └── src/main/java/com/kvstore/client/KVStoreClient.java
├── docker/
│   ├── Dockerfile                   # Multi-stage build
│   └── docker-compose.yml           # 5-node cluster
└── README.md
```

---

## Configuration (Environment Variables)

| Variable | Default | Description |
|---|---|---|
| `NODE_ID` | `node-1` | Unique identifier for this node |
| `NODE_PORT` | `8081` | HTTP port this node listens on |
| `PRIMARY_HOST` | `node-1` | Node ID of the primary (coordinator) |
| `PEER_NODES` | _(empty)_ | Comma-separated list of `host:port` for all nodes |
| `REPLICATION_FACTOR` | `3` | Number of nodes each key is written to |
| `DATA_DIR` | `/data` | Directory for WAL (`wal.log`) and snapshot (`snapshot.json`) |

---

## Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 17 | Language |
| Spring Boot | 3.2 | REST framework, scheduling |
| Maven | 3.9 | Build system |
| Docker + Compose | 24 / v2 | Container orchestration |
| JUnit 5 + Mockito | 5.x | Unit testing |
