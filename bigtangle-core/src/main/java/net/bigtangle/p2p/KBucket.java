package net.bigtangle.p2p;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class KBucket {

    private final int capacity;
    private final int maxFailedPings;
    private final long staleTimeoutMillis;
    private final LinkedList<KBucketEntry> entries;
    private final LinkedList<KBucketEntry> replacementCache;

    public KBucket() {
        this(16, 3, 60 * 60 * 1000L);
    }

    public KBucket(int capacity, int maxFailedPings, long staleTimeoutMillis) {
        this.capacity = capacity;
        this.maxFailedPings = maxFailedPings;
        this.staleTimeoutMillis = staleTimeoutMillis;
        this.entries = new LinkedList<>();
        this.replacementCache = new LinkedList<>();
    }

    public synchronized boolean insert(KBucketEntry entry) {
        int existingIdx = indexOf(entry.getNodeId());
        if (existingIdx >= 0) {
            KBucketEntry existing = entries.get(existingIdx);
            existing.markSeen();
            entries.remove(existingIdx);
            entries.addLast(existing);
            return true;
        }

        if (entries.size() < capacity) {
            entries.addLast(entry);
            return true;
        }

        for (KBucketEntry e : entries) {
            if (e.isStale(staleTimeoutMillis) || e.shouldEvict(maxFailedPings)) {
                replacementCache.addFirst(e);
                entries.remove(e);
                entries.addLast(entry);
                trimReplacementCache();
                return true;
            }
        }

        replacementCache.addFirst(entry);
        trimReplacementCache();
        return false;
    }

    public synchronized boolean remove(NodeId nodeId) {
        int idx = indexOf(nodeId);
        if (idx >= 0) {
            entries.remove(idx);
            if (!replacementCache.isEmpty()) {
                entries.addLast(replacementCache.removeFirst());
            }
            return true;
        }
        replacementCache.removeIf(e -> e.getNodeId().equals(nodeId));
        return false;
    }

    public synchronized Optional<KBucketEntry> find(NodeId nodeId) {
        int idx = indexOf(nodeId);
        if (idx >= 0) {
            return Optional.of(entries.get(idx));
        }
        return Optional.empty();
    }

    public synchronized List<KBucketEntry> getEntries() {
        return new ArrayList<>(entries);
    }

    public synchronized List<KBucketEntry> getReplacementCache() {
        return new ArrayList<>(replacementCache);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean isFull() {
        return entries.size() >= capacity;
    }

    public synchronized void evictStale() {
        entries.removeIf(e -> e.isStale(staleTimeoutMillis) || e.shouldEvict(maxFailedPings));
        while (entries.size() < capacity && !replacementCache.isEmpty()) {
            entries.addLast(replacementCache.removeFirst());
        }
    }

    private int indexOf(NodeId nodeId) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getNodeId().equals(nodeId)) {
                return i;
            }
        }
        return -1;
    }

    private void trimReplacementCache() {
        while (replacementCache.size() > capacity) {
            replacementCache.removeLast();
        }
    }
}
