package net.bigtangle.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Optional;

public class KBucketTest {

    private NodeRecord createRecord(String host, NodeRecord.KeyPair kp) {
        return NodeRecord.createSelf(kp, host, 30303, 30304, 1);
    }

    @Test
    public void testInsertFirstEntry() {
        KBucket bucket = new KBucket(16, 3, 3600000L);
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord record = createRecord("host1", kp);
        assertTrue(bucket.insert(new KBucketEntry(record)));
        assertEquals(1, bucket.size());
    }

    @Test
    public void testInsertDuplicateMovesToEnd() {
        KBucket bucket = new KBucket(16, 3, 3600000L);
        NodeRecord.KeyPair kp1 = NodeRecord.generateKeyPair();
        NodeRecord.KeyPair kp2 = NodeRecord.generateKeyPair();
        NodeRecord r1 = createRecord("host1", kp1);
        NodeRecord r2 = createRecord("host2", kp2);
        bucket.insert(new KBucketEntry(r1));
        bucket.insert(new KBucketEntry(r2));
        assertEquals(2, bucket.size());
        assertTrue(bucket.insert(new KBucketEntry(r1)));
        assertEquals(2, bucket.size());
    }

    @Test
    public void testInsertFullBucketEvictsStale() {
        KBucket bucket = new KBucket(2, 1, 100L);
        NodeRecord.KeyPair kp1 = NodeRecord.generateKeyPair();
        NodeRecord.KeyPair kp2 = NodeRecord.generateKeyPair();
        NodeRecord.KeyPair kp3 = NodeRecord.generateKeyPair();
        NodeRecord r1 = createRecord("host1", kp1);
        NodeRecord r2 = createRecord("host2", kp2);
        NodeRecord r3 = createRecord("host3", kp3);

        KBucketEntry e1 = new KBucketEntry(r1, System.currentTimeMillis() - 200, 2);
        bucket.insert(e1);
        bucket.insert(new KBucketEntry(r2));

        assertTrue(bucket.insert(new KBucketEntry(r3)));
        assertEquals(2, bucket.size());
    }

    @Test
    public void testInsertFullBucketNoStaleGoesToReplacementCache() {
        KBucket bucket = new KBucket(2, 3, 3600000L);
        NodeRecord.KeyPair kp1 = NodeRecord.generateKeyPair();
        NodeRecord.KeyPair kp2 = NodeRecord.generateKeyPair();
        NodeRecord.KeyPair kp3 = NodeRecord.generateKeyPair();
        bucket.insert(new KBucketEntry(createRecord("host1", kp1)));
        bucket.insert(new KBucketEntry(createRecord("host2", kp2)));

        assertFalse(bucket.insert(new KBucketEntry(createRecord("host3", kp3))));
        assertEquals(2, bucket.size());
        assertEquals(1, bucket.getReplacementCache().size());
    }

    @Test
    public void testRemove() {
        KBucket bucket = new KBucket(16, 3, 3600000L);
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord record = createRecord("host1", kp);
        bucket.insert(new KBucketEntry(record));
        assertTrue(bucket.remove(record.getNodeId()));
        assertEquals(0, bucket.size());
    }

    @Test
    public void testRemoveNonExistent() {
        KBucket bucket = new KBucket(16, 3, 3600000L);
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord record = createRecord("host1", kp);
        assertFalse(bucket.remove(record.getNodeId()));
    }

    @Test
    public void testFind() {
        KBucket bucket = new KBucket(16, 3, 3600000L);
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord record = createRecord("host1", kp);
        bucket.insert(new KBucketEntry(record));
        Optional<KBucketEntry> found = bucket.find(record.getNodeId());
        assertTrue(found.isPresent());
        assertEquals(record.getNodeId(), found.get().getNodeId());
    }

    @Test
    public void testFindNonExistent() {
        KBucket bucket = new KBucket(16, 3, 3600000L);
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord record = createRecord("host1", kp);
        assertTrue(bucket.find(record.getNodeId()).isEmpty());
    }

    @Test
    public void testEvictStale() {
        KBucket bucket = new KBucket(16, 1, 100L);
        NodeRecord.KeyPair kp1 = NodeRecord.generateKeyPair();
        NodeRecord.KeyPair kp2 = NodeRecord.generateKeyPair();
        KBucketEntry fresh = new KBucketEntry(createRecord("fresh", kp1));
        KBucketEntry stale = new KBucketEntry(createRecord("stale", kp2), System.currentTimeMillis() - 200, 2);
        bucket.insert(stale);
        bucket.insert(fresh);
        bucket.evictStale();
        assertEquals(1, bucket.size());
        assertEquals("fresh", bucket.getEntries().get(0).getRecord().getHost());
    }

    @Test
    public void testReplacementCachePromotedOnRemove() {
        KBucket bucket = new KBucket(1, 3, 3600000L);
        NodeRecord.KeyPair kp1 = NodeRecord.generateKeyPair();
        NodeRecord.KeyPair kp2 = NodeRecord.generateKeyPair();
        NodeRecord r1 = createRecord("host1", kp1);
        NodeRecord r2 = createRecord("host2", kp2);
        bucket.insert(new KBucketEntry(r1));
        bucket.insert(new KBucketEntry(r2));
        assertEquals(1, bucket.size());
        assertEquals(1, bucket.getReplacementCache().size());

        bucket.remove(r1.getNodeId());
        assertEquals(1, bucket.size());
        assertEquals("host2", bucket.getEntries().get(0).getRecord().getHost());
    }

    @Test
    public void testMarkSeenUpdatesTimestamp() {
        KBucket bucket = new KBucket(16, 3, 3600000L);
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord record = createRecord("host1", kp);
        KBucketEntry entry = new KBucketEntry(record, System.currentTimeMillis() - 1000, 0);
        long before = entry.getLastSeen();
        bucket.insert(entry);
        assertTrue(bucket.find(record.getNodeId()).get().getLastSeen() >= before);
    }
}
