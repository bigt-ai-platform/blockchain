package net.bigtangle.p2p;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class PeerManager {

    private static final Logger log = LoggerFactory.getLogger(PeerManager.class);

    private final PeerConfiguration config;
    private final RoutingTable routingTable;
    private final Map<NodeId, PeerScore> scores = new ConcurrentHashMap<>();
    private final ActivePeerPool activePool;
    private final NodeRecord selfRecord;
    private final byte[] privateKey;
    private DiscoveryService discoveryService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @Autowired
    public PeerManager(PeerConfiguration config) {
        this.config = config;
        NodeRecord.KeyPair keyPair = NodeRecord.generateKeyPair();
        this.privateKey = keyPair.privateKey;
        this.selfRecord = NodeRecord.createSelf(keyPair, "0.0.0.0", config.getUdpPort(), config.getTcpPort(), 1);
        this.routingTable = new RoutingTable(selfRecord.getNodeId(), config.getBucketSize(), 3, 3600000L);
        this.activePool = new ActivePeerPool(config.getActivePeers(), config.getMinValidators());
    }

    public PeerManager(PeerConfiguration config, NodeRecord selfRecord, byte[] privateKey) {
        this.config = config;
        this.selfRecord = selfRecord;
        this.privateKey = privateKey;
        this.routingTable = new RoutingTable(selfRecord.getNodeId(), config.getBucketSize(), 3, 3600000L);
        this.activePool = new ActivePeerPool(config.getActivePeers(), config.getMinValidators());
    }

    @PostConstruct
    public void start() throws IOException {
        startDiscovery();
        bootstrap();
        scheduler.scheduleAtFixedRate(this::refresh, 30, 30, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::scoreAndEvict, 5, 5, TimeUnit.MINUTES);
        log.info("PeerManager started, nodeId={}", selfRecord.getNodeId());
    }

    @PreDestroy
    public void stop() {
        if (discoveryService != null) discoveryService.stop();
        scheduler.shutdownNow();
        log.info("PeerManager stopped");
    }

    private void startDiscovery() throws IOException {
        this.discoveryService = new DiscoveryService(
                config.getUdpPort(), routingTable, selfRecord, privateKey);
        discoveryService.start();
    }

    private void bootstrap() {
        if (!config.getBootnodes().isEmpty()) {
            for (String bootnodeStr : config.getBootnodes()) {
                try {
                    if (bootnodeStr.startsWith("enr:")) {
                        NodeRecord bootnode = NodeRecord.fromEnr(bootnodeStr);
                        routingTable.update(bootnode);
                        log.info("Added ENR bootnode {}", bootnode.getNodeId());
                    } else {
                        String[] parts = bootnodeStr.split(":");
                        if (parts.length < 2) {
                            log.warn("Invalid bootnode format: {}", bootnodeStr);
                            continue;
                        }
                        String host = parts[0];
                        int port = Integer.parseInt(parts[1]);
                        NodeRecord.KeyPair dummy = NodeRecord.generateKeyPair();
                        NodeRecord bootnode = NodeRecord.createSelf(dummy,
                                host, config.getUdpPort(), port, 0);
                        routingTable.update(bootnode);
                        log.info("Added bootnode {}:{}", host, port);
                    }
                } catch (Exception e) {
                    log.warn("Failed to add bootnode {}: {}", bootnodeStr, e.getMessage());
                }
            }
        } else {
            bootstrapFromDns();
        }
    }

    private void bootstrapFromDns() {
        List<String> seeds = config.getDnsSeeds();
        if (seeds.isEmpty()) {
            log.info("No DNS seeds configured, skipping DNS bootstrap");
            return;
        }
        for (String seed : seeds) {
            try {
                List<NodeRecord> records = DnsDiscoveryResolver.resolve(seed);
                for (NodeRecord record : records) {
                    routingTable.update(record);
                }
                log.info("DNS seed {} resolved to {} peers", seed, records.size());
            } catch (Exception e) {
                log.warn("Failed to resolve DNS seed {}: {}", seed, e.getMessage());
            }
        }
    }

    private void refresh() {
        try {
            routingTable.evictStale();
        } catch (Exception e) {
            log.debug("Refresh error: {}", e.getMessage());
        }
    }

    private void scoreAndEvict() {
        List<NodeRecord> all = routingTable.getAllEntries();
        long maxChain = all.stream()
                .mapToLong(r -> scores.getOrDefault(r.getNodeId(), new PeerScore(r.getNodeId())).getChainLength())
                .max().orElse(1);

        List<ScoredPeer> scored = new ArrayList<>();
        for (NodeRecord record : all) {
            PeerScore score = scores.computeIfAbsent(record.getNodeId(), PeerScore::new);
            double s = score.compute(maxChain);
            scored.add(new ScoredPeer(record, s));
        }

        scored.sort(Comparator.comparingDouble(ScoredPeer::score).reversed());

        List<NodeRecord> activeCandidates = scored.stream()
                .filter(sp -> sp.score() >= config.getScoreFloor())
                .map(ScoredPeer::record)
                .collect(Collectors.toList());

        activePool.update(activeCandidates, routingTable);
    }

    public List<NodeRecord> getActivePeers() {
        return activePool.getActivePeers();
    }

    public List<NodeRecord> findClosest(NodeId target, int k) {
        return routingTable.findClosest(target, k);
    }

    public boolean updatePeer(NodeRecord record) {
        boolean added = routingTable.update(record);
        if (added) {
            scores.computeIfAbsent(record.getNodeId(), PeerScore::new);
        }
        return added;
    }

    public boolean removePeer(NodeId nodeId) {
        scores.remove(nodeId);
        return routingTable.remove(nodeId);
    }

    public PeerScore getScore(NodeId nodeId) {
        return scores.get(nodeId);
    }

    public NodeRecord getSelfRecord() { return selfRecord; }
    public RoutingTable getRoutingTable() { return routingTable; }
    public DiscoveryService getDiscoveryService() { return discoveryService; }
    public ActivePeerPool getActivePool() { return activePool; }

    private record ScoredPeer(NodeRecord record, double score) {}
}
