package net.bigtangle.crypto.pq;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import net.bigtangle.crypto.pq.PQConstants;

/**
 * A versioned bundle of public keys used in post-quantum addresses and
 * transaction inputs.  Entries are sorted by algorithm ID so the canonical
 * encoding is deterministic across all nodes.
 */
public final class KeyBundle {

    private final int version;
    private final List<Entry> entries;

    /** A single key entry within the bundle. */
    public static final class Entry {
        private final int algorithm;
        private final byte[] publicKey;

        public Entry(int algorithm, byte[] publicKey) {
            if (publicKey == null) throw new NullPointerException("publicKey");
            this.algorithm = algorithm;
            this.publicKey = publicKey.clone();
        }

        public int algorithm() { return algorithm; }
        public byte[] publicKey() { return publicKey.clone(); }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Entry)) return false;
            Entry e = (Entry) o;
            return algorithm == e.algorithm && Arrays.equals(publicKey, e.publicKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(algorithm, Arrays.hashCode(publicKey));
        }
    }

    /**
     * Create a bundle from entries (will be sorted by algorithm ID).
     */
    public KeyBundle(List<Entry> entries) {
        this(PQConstants.BUNDLE_VERSION, entries);
    }

    public KeyBundle(int version, List<Entry> entries) {
        this.version = version;
        List<Entry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.algorithm, b.algorithm));
        this.entries = Collections.unmodifiableList(sorted);
    }

    public int version() { return version; }
    public List<Entry> entries() { return entries; }

    /**
     * Find an entry by algorithm ID, or null.
     */
    public Entry getEntry(int algorithm) {
        for (Entry e : entries) {
            if (e.algorithm == algorithm) return e;
        }
        return null;
    }

    /**
     * Canonical serialization: sorted entries, deterministic by algorithm ID.
     *
     * <pre>{@code
     *   version   uint8
     *   entries   uint8
     *   for each:  algorithm uint8 | length uint16 | public_key bytes[length]
     * }</pre>
     */
    public byte[] serialize() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(version);
            bos.write(entries.size());
            for (Entry e : entries) {
                bos.write(e.algorithm);
                byte[] pk = e.publicKey;
                bos.write((pk.length >>> 8) & 0xFF);
                bos.write(pk.length & 0xFF);
                bos.write(pk);
            }
            return bos.toByteArray();
        } catch (IOException unexpected) {
            throw new RuntimeException(unexpected);
        }
    }

    /**
     * Deserialize from the canonical form.
     */
    public static KeyBundle deserialize(byte[] bytes) {
        if (bytes.length < 2) throw new IllegalArgumentException("too short");
        int version = bytes[0] & 0xFF;
        int count = bytes[1] & 0xFF;
        List<Entry> entries = new ArrayList<>(count);
        int offset = 2;
        for (int i = 0; i < count; i++) {
            if (offset + 4 > bytes.length) throw new IllegalArgumentException("truncated entry");
            int algorithm = bytes[offset++] & 0xFF;
            int length = ((bytes[offset++] & 0xFF) << 8) | (bytes[offset++] & 0xFF);
            if (offset + length > bytes.length) throw new IllegalArgumentException("truncated key bytes");
            byte[] pk = Arrays.copyOfRange(bytes, offset, offset + length);
            offset += length;
            entries.add(new Entry(algorithm, pk));
        }
        // Re-sort to canonical order in case the serialized form wasn't sorted
        if (version > PQConstants.BUNDLE_VERSION)
            throw new IllegalArgumentException("unsupported bundle version: " + version);
        return new KeyBundle(version, entries);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof KeyBundle)) return false;
        KeyBundle other = (KeyBundle) o;
        return version == other.version && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, entries);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("KeyBundle{v=").append(version);
        for (Entry e : entries) {
            sb.append(" alg=").append(e.algorithm);
            sb.append(" len=").append(e.publicKey.length);
        }
        return sb.append("}").toString();
    }
}
