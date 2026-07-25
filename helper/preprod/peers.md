# Peer Discovery & Management Design

Replaces the current static configuration-driven peer model with a
Kademlia-inspired dynamic system, following Ethereum's devp2p pattern.

## Current Problems

| Problem | Detail |
|---------|--------|
| Static peer list | `server.requester` / `pos.gossipPeers` are set at startup, never change |
| No peer exchange | Servers never share peer lists; no way to discover new nodes |
| `ServerPool` orphaned | Client-only, never used by server; `checkServers()` is broken (appends duplicates), sorting is commented out |
| Kafka producer per message | `BlockSaveService` creates a new `KafkaProducer` connection on every `broadcastBytes()` |
| Sync from single peer | `syncChain` picks the best peer and fetches from it alone |
| No authentication | Plain HTTP, no node identity verification |

## Target Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    Kademlia Routing Table                │
│                    (256 k-buckets, XOR distance)         │
│                                                          │
│  Bucket 0  │  Bucket 1  │  ...  │  Bucket 255            │
│  [n1, n2]  │  [n3]      │       │  [nK]                  │
└──────────────────────┬───────────────────────────────────┘
                       │
         ┌─────────────┼─────────────┐
         ▼             ▼             ▼
   ┌──────────┐ ┌──────────┐ ┌──────────┐
   │ discv5   │ │ RLPx     │ │ DNS      │
   │ UDP      │ │ TCP      │ │ discovery│
   │ FINDNODE │ │ session  │ │ EIP-1459 │
   │ PING/PONG│ │ encrypt  │ │          │
   └──────────┘ └──────────┘ └──────────┘
```

## Components

### 1. Node Identity (ENR-equivalent)

Replace bare URL strings with a signed node record.

```java
public class NodeRecord {
    private String nodeId;       // SHA-256(publicKey)
    private byte[] publicKey;    // secp256k1
    private String host;         // IP or hostname
    private int udpPort;         // discovery port
    private int tcpPort;         // RLPx / HTTP port
    private long seq;            // sequence number, incremented on update
    private byte[] signature;    // self-signed
}
```

Each node generates an Ed25519 or secp256k1 keypair at startup. The
`nodeId` is `SHA-256(publicKey)`. Every message and peer record is
signed. Nodes verify signatures before accepting a peer.

Storage: cached in memory, optionally persisted to DB for restarts.

### 2. Kademlia Routing Table

Replace `ArrayList<ServerState>` with a proper routing table.

```
K-Bucket
├── capacity: 16 entries
├── replacement cache (for evicted but still valid entries)
└── entries sorted by last-seen (LRU eviction)
```

```java
public class RoutingTable {
    private KBucket[] buckets = new KBucket[256];

    // XOR distance: nodeId ^ target
    int bucketIndex(byte[] nodeId, byte[] target);

    // Insert or update; evict stale entries
    void update(NodeRecord record);

    // Return k closest nodes to target (k=16)
    List<NodeRecord> findClosest(byte[] target);

    // Periodic: ping oldest, evict if no pong
    void evictStale();
}
```

XOR distance = `nodeId ^ target`, interpreted as big-endian integer.
Bucket index = leading zero bits of distance (bit length 255 - log2(dist)).

### 3. Discovery Protocol (discv5-inspired, UDP)

Minimal set of message types:

| Message | Direction | Purpose |
|---------|-----------|---------|
| `PING` | both | Liveness check + endpoint verification |
| `PONG` | both | Response to PING; includes echoed address |
| `FINDNODE` | req | Query closest nodes to target |
| `NODES` | resp | Up to 16 node records |
| `ENR` | both | Direct record exchange |

Packet format:

```java
public class DiscoveryMessage {
    byte[] magic;        // "bgl-discv5" header
    int messageType;     // 0=PING, 1=PONG, 2=FINDNODE, 3=NODES, 4=ENR
    byte[] nodeId;       // sender
    byte[] payload;      // type-specific, CBOR or JSON
    byte[] signature;    // signed(nodeId || payload)
}
```

All messages are UDP. Responses must be received within 5 seconds or
the node is considered stale.

#### FINDNODE Iterative Lookup

```
1. Pick target = random nodeId
2. Query k closest known nodes for FINDNODE(target)
3. Merge responses into shortlist
4. Pick k closest not-yet-queried, repeat
5. Stop when no closer nodes found (alpha = 3 concurrency)
```

Used for:
- Bootstrapping (query bootnode with random target)
- Periodic refresh (random target every 30 min)
- Peer count maintenance (if table < threshold)

### 4. Session Protocol (RLPx-inspired, TCP)

After discovery, nodes establish an encrypted, authenticated TCP session.

```
1. TCP connect to discovered host:port
2. ECDH handshake (EIP-8 style)
3. Frame encryption (AES-GCM)
4. Exchange Hello (capabilities, chain ID)
```

For the current codebase, this can be layered incrementally:
- Phase 1: TCP with plain Hello + capability negotiation
- Phase 2: Add encryption

Capabilities advertised in Hello:
- `bgl-sync/1` — block synchronization
- `bgl-tx/1` — transaction gossip
- `bgl-pos/1` — attestation & beacon block gossip

### 5. Peer Scoring

Replace the unused `SortbyChain` with a comprehensive scoring system.

```java
public class PeerScore {
    double chainLengthScore;
    double responseTimeScore;
    double successRate;       // successful / total requests
    double ageInHours;        // how long we've known this peer
    double stakeWeight;       // if PoS: validator stake
    double lastComputed;      // timestamp
}
```

Combined score = `w1 * chain + w2 * rtt + w3 * success + w4 * age + w5 * stake`

- Peers below floor score are evicted from routing table
- Peers above threshold are candidates for active connection pool
- Scoring runs every 5 minutes via scheduled task

### 6. Active Peer Pool

A separate pool of `n` (configurable, default 8) actively connected peers
used for block/tx sync.

```
Active Pool
├── max 8 connections
├── at least 2 must be validators (if PoS enabled)
├── replaces on disconnect or score drop
└── round-robin for request distribution
```

### 7. Integration Points

#### Replace SyncBlockService static requester

```java
@Service
public class SyncBlockService {
    @Autowired
    private PeerManager peerManager;

    // Before:
    // String[] re = serverConfiguration.getRequester().split(",");

    // After:
    List<NodeRecord> peers = peerManager.getActivePeers(
        PeerCapability.BLOCK_SYNC
    );
```

#### Replace GossipService static gossipPeers

```java
@Service
public class GossipService {
    @Autowired
    private PeerManager peerManager;

    private void broadcast(String path, byte[] body) {
        List<NodeRecord> peers = peerManager.getActivePeers(
            PeerCapability.POS_GOSSIP
        );
        // Fan-out with CompletableFuture, 5s timeout
        ...
    }
```

#### Replace BlockSaveService Kafka-per-call

```java
@Component
public class BroadcastService {
    private KafkaMessageProducer kafkaProducer; // singleton @Bean

    private List<NodeRecord> syncPeers; // from PeerManager

    public void broadcastBlock(Block block) {
        // 1. Kafka for nodes that subscribe
        if (kafkaConfigured) kafkaProducer.send(...);

        // 2. Direct P2P to connected peers
        for (NodeRecord peer : syncPeers) {
            asyncHttpPost(peer, "/submitBlock", block.serialize());
        }
    }
```

### 8. DNS Discovery (EIP-1459)

Optional bootstrap fallback. A Merkle tree of node records published as
DNS TXT records.

```
enrtree://ABC123@nodes.example.com

TXT "enrtree-root:v1 e=ABCD seq=1 sig=XYZ"
TXT "ABCD.enrtree://...." (branch nodes)
TXT "XYZ.enr:-..." (leaf node records)
```

Implementation can use OkHttp3 + DNS-over-HTTPS for resolution.

## Deployment Plan

### Phase 1 — Routing Table + Node Identity

1. Implement `NodeRecord` with Ed25519 signing
2. Implement `RoutingTable` with 256 k-buckets, XOR distance, LRU eviction
3. Unit tests: insert, evict, findClosest

### Phase 2 — discv5 UDP Discovery

1. Implement `DiscoveryService` — UDP listener + 4 message types
2. Bootstrap: load bootnodes from config, iterative FINDNODE
3. Periodic refresh: random lookup every 30 min

### Phase 3 — Peer Manager + Scoring

1. Implement `PeerManager` — facade combining RoutingTable + Discovery
2. Scoring engine: chain length, response time, success rate
3. Active peer pool: maintain N connections by score

### Phase 4 — Replace Static Configs

1. `SyncBlockService` → query `PeerManager.getActivePeers(BLOCK_SYNC)`
2. `GossipService` → query `PeerManager.getActivePeers(POS_GOSSIP)`
3. Expose `/getPeers` REST endpoint from PeerManager

### Phase 5 — TCP Sessions + Encryption

1. Add RLPx-style TCP handshake
2. Capability negotiation (bgl-sync, bgl-tx, bgl-pos)

### Phase 6 — DNS Discovery

1. Implement ENR Merkle tree builder
2. DNS resolver via DNS-over-HTTPS

## Client Discovery Flow

A client (wallet, new node, monitoring tool) discovers peers in three ways:

### 1. HTTP REST API (`GET /getPeers`)

The simplest method. Hit any running node's HTTP endpoint:

```bash
curl http://localhost:8081/getPeers | jq .
```

Returns `self` (the responding node's ID), `count`, and `peers` array with
nodeId, host, tcpPort, udpPort, score, chainLength, and responseTime for each
known peer.

### 2. UDP Discovery (`PeerDiscoveryClient`)

Performs a Kademlia iterative `FINDNODE` lookup over UDP. The client sends a
`FINDNODE` request with a random target to known bootnodes, which respond with
the 16 closest nodes. The client then queries those, repeating until no closer
nodes are found. No persistent listener required — one-shot `DatagramSocket`
with timeout.

```java
// From a list of ENR bootnodes
List<NodeRecord> bootnodes = List.of(
    NodeRecord.fromEnr("enr:abc..."),
    NodeRecord.fromEnr("enr:def...")
);
PeerDiscoveryClient client = new PeerDiscoveryClient(bootnodes);
List<NodeRecord> peers = client.discover(50); // find up to 50 peers
```

The client is included in `bigtangle-core` and has no Spring dependency — usable
from wallets, CLIs, or any JVM process.

### 3. ENR Configuration

Bootnodes are configured in `application.yml` as ENR strings or `host:port`:

```yaml
peer:
  bootnodes:
    - enr:5a725da3fcb7...f144199a499abf29
    - 81.169.156.203:8089
```

The `PeerManager.bootstrap()` method parses both formats at startup.
ENR format is a hex-encoded signed `NodeRecord` with prefix `enr:`.

```java
String enr = record.toEnr();            // serialize
NodeRecord parsed = NodeRecord.fromEnr(enr); // deserialize
```

## Configuration

```yaml
peer:
  # Boot nodes for initial discovery
  bootnodes:
    - enr:-IC...node0
    - enr:-IC...node1

  # Listen ports
  udp-port: 30303
  tcp-port: 30304

  # Routing table
  bucket-size: 16
  max-peers: 100

  # Active pool
  active-peers: 8
  min-validators: 2

  # Scoring
  score-floor: 0.1
  score-weight-chain: 0.3
  score-weight-rtt: 0.3
  score-weight-success: 0.2
  score-weight-age: 0.1
  score-weight-stake: 0.1
```


