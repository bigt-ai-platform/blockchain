package net.bigtangle.p2p;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class RoutingTable {

    private static final int BUCKET_COUNT = 256;
    private final KBucket[] buckets;
    private final NodeId selfId;

    public RoutingTable(NodeId selfId) {
        this(selfId, 16, 3, 60 * 60 * 1000L);
    }

    public RoutingTable(NodeId selfId, int bucketCapacity, int maxFailedPings, long staleTimeoutMillis) {
        this.selfId = selfId;
        this.buckets = new KBucket[BUCKET_COUNT];
        for (int i = 0; i < BUCKET_COUNT; i++) {
            buckets[i] = new KBucket(bucketCapacity, maxFailedPings, staleTimeoutMillis);
        }
    }

    public synchronized boolean update(NodeRecord record) {
        NodeId targetId = record.getNodeId();
        if (targetId.equals(selfId)) return false;
        int bucketIndex = selfId.bucketIndex(targetId);
        return buckets[bucketIndex].insert(new KBucketEntry(record));
    }

    public synchronized boolean remove(NodeId nodeId) {
        if (nodeId.equals(selfId)) return false;
        int bucketIndex = selfId.bucketIndex(nodeId);
        return buckets[bucketIndex].remove(nodeId);
    }

    public synchronized List<NodeRecord> findClosest(NodeId target, int k) {
        List<KBucketEntry> all = new ArrayList<>();
        int targetBucket = selfId.bucketIndex(target);

        all.addAll(buckets[targetBucket].getEntries());

        int lower = targetBucket - 1;
        int upper = targetBucket + 1;
        while ((lower >= 0 || upper < BUCKET_COUNT) && all.size() < k * 2) {
            if (lower >= 0) {
                all.addAll(buckets[lower].getEntries());
                lower--;
            }
            if (upper < BUCKET_COUNT) {
                all.addAll(buckets[upper].getEntries());
                upper++;
            }
        }

        all.sort(Comparator.comparingInt(e -> distanceToBucket(target, e.getNodeId())));
        List<NodeRecord> result = new ArrayList<>();
        for (KBucketEntry entry : all) {
            if (result.size() >= k) break;
            result.add(entry.getRecord());
        }
        return result;
    }

    public synchronized void evictStale() {
        for (KBucket bucket : buckets) {
            bucket.evictStale();
        }
    }

    public synchronized int totalEntries() {
        int count = 0;
        for (KBucket bucket : buckets) {
            count += bucket.size();
        }
        return count;
    }

    public synchronized List<NodeRecord> getAllEntries() {
        List<NodeRecord> all = new ArrayList<>();
        for (KBucket bucket : buckets) {
            for (KBucketEntry entry : bucket.getEntries()) {
                all.add(entry.getRecord());
            }
        }
        return all;
    }

    public NodeId getSelfId() {
        return selfId;
    }

    private int distanceToBucket(NodeId target, NodeId candidate) {
        byte[] xorTarget = target.xor(candidate);
        for (int i = 0; i < 32; i++) {
            if (xorTarget[i] != 0) {
                int bitPos = i * 8 + (7 - (Integer.numberOfLeadingZeros(xorTarget[i] & 0xFF) - 24));
                return 255 - bitPos;
            }
        }
        return 0;
    }
}
