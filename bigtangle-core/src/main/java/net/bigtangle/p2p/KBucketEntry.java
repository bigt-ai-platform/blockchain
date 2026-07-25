package net.bigtangle.p2p;

import java.util.Objects;

public class KBucketEntry {

    private final NodeRecord record;
    private volatile long lastSeen;
    private volatile int failedPings;

    public KBucketEntry(NodeRecord record) {
        this(record, System.currentTimeMillis(), 0);
    }

    public KBucketEntry(NodeRecord record, long lastSeen, int failedPings) {
        this.record = Objects.requireNonNull(record);
        this.lastSeen = lastSeen;
        this.failedPings = failedPings;
    }

    public NodeRecord getRecord() { return record; }
    public long getLastSeen() { return lastSeen; }
    public int getFailedPings() { return failedPings; }

    public void markSeen() {
        this.lastSeen = System.currentTimeMillis();
        this.failedPings = 0;
    }

    public void markFailedPing() {
        this.failedPings++;
    }

    public boolean isStale(long timeoutMillis) {
        return System.currentTimeMillis() - lastSeen > timeoutMillis;
    }

    public boolean shouldEvict(int maxFailedPings) {
        return failedPings >= maxFailedPings;
    }

    public NodeId getNodeId() {
        return record.getNodeId();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KBucketEntry that = (KBucketEntry) o;
        return record.getNodeId().equals(that.record.getNodeId());
    }

    @Override
    public int hashCode() {
        return record.getNodeId().hashCode();
    }

    @Override
    public String toString() {
        return "KBucketEntry{id=" + record.getNodeId() + " lastSeen=" + lastSeen + " fails=" + failedPings + "}";
    }
}
