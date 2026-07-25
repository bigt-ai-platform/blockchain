package net.bigtangle.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ActivePeerPool {

    private static final Logger log = LoggerFactory.getLogger(ActivePeerPool.class);

    private final int maxSize;
    private final int minValidators;
    private final CopyOnWriteArrayList<NodeRecord> activePeers = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public ActivePeerPool(int maxSize, int minValidators) {
        this.maxSize = maxSize;
        this.minValidators = minValidators;
    }

    public synchronized void update(List<NodeRecord> candidates, RoutingTable routingTable) {
        Set<NodeId> candidateIds = candidates.stream()
                .map(NodeRecord::getNodeId)
                .collect(Collectors.toSet());

        activePeers.removeIf(p -> !candidateIds.contains(p.getNodeId()));

        for (NodeRecord candidate : candidates) {
            if (activePeers.size() >= maxSize) break;
            if (activePeers.stream().noneMatch(p -> p.getNodeId().equals(candidate.getNodeId()))) {
                activePeers.add(candidate);
                log.debug("Added peer {} to active pool", candidate.getNodeId());
            }
        }
    }

    public synchronized void onDisconnect(NodeId nodeId) {
        activePeers.removeIf(p -> p.getNodeId().equals(nodeId));
    }

    public synchronized void onScoreDrop(NodeId nodeId) {
        activePeers.removeIf(p -> p.getNodeId().equals(nodeId));
    }

    public synchronized NodeRecord getNextPeer() {
        if (activePeers.isEmpty()) return null;
        int idx = roundRobinIndex.getAndIncrement() % activePeers.size();
        return activePeers.get(idx);
    }

    public synchronized List<NodeRecord> getActivePeers() {
        return new ArrayList<>(activePeers);
    }

    public synchronized int size() {
        return activePeers.size();
    }

    public synchronized boolean isFull() {
        return activePeers.size() >= maxSize;
    }
}
