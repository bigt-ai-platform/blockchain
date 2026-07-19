package net.bigtangle.crypto.pq;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A versioned bundle of signatures matching a KeyBundle.  Each entry
 * identifies its algorithm and carries the raw signature bytes.
 */
public final class SignatureBundle {

    private final int version;
    private final List<Entry> entries;

    public static final class Entry {
        private final int algorithm;
        private final byte[] signature;

        public Entry(int algorithm, byte[] signature) {
            if (signature == null) throw new NullPointerException("signature");
            this.algorithm = algorithm;
            this.signature = signature.clone();
        }

        public int algorithm() { return algorithm; }
        public byte[] signature() { return signature.clone(); }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Entry)) return false;
            Entry e = (Entry) o;
            return algorithm == e.algorithm && Arrays.equals(signature, e.signature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(algorithm, Arrays.hashCode(signature));
        }
    }

    public SignatureBundle(List<Entry> entries) {
        this(PQConstants.BUNDLE_VERSION, entries);
    }

    public SignatureBundle(int version, List<Entry> entries) {
        this.version = version;
        List<Entry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.algorithm, b.algorithm));
        this.entries = Collections.unmodifiableList(sorted);
    }

    public int version() { return version; }
    public List<Entry> entries() { return entries; }

    public Entry getEntry(int algorithm) {
        for (Entry e : entries) {
            if (e.algorithm == algorithm) return e;
        }
        return null;
    }

    /**
     * Canonical serialization matching KeyBundle layout.
     */
    public byte[] serialize() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(version);
            bos.write(entries.size());
            for (Entry e : entries) {
                bos.write(e.algorithm);
                byte[] sig = e.signature;
                bos.write((sig.length >>> 8) & 0xFF);
                bos.write(sig.length & 0xFF);
                bos.write(sig);
            }
            return bos.toByteArray();
        } catch (IOException unexpected) {
            throw new RuntimeException(unexpected);
        }
    }

    public static SignatureBundle deserialize(byte[] bytes) {
        if (bytes.length < 2) throw new IllegalArgumentException("too short");
        int version = bytes[0] & 0xFF;
        int count = bytes[1] & 0xFF;
        List<Entry> entries = new ArrayList<>(count);
        int offset = 2;
        for (int i = 0; i < count; i++) {
            if (offset + 4 > bytes.length) throw new IllegalArgumentException("truncated entry");
            int algorithm = bytes[offset++] & 0xFF;
            int length = ((bytes[offset++] & 0xFF) << 8) | (bytes[offset++] & 0xFF);
            if (offset + length > bytes.length) throw new IllegalArgumentException("truncated sig bytes");
            byte[] sig = Arrays.copyOfRange(bytes, offset, offset + length);
            offset += length;
            entries.add(new Entry(algorithm, sig));
        }
        return new SignatureBundle(version, entries);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SignatureBundle)) return false;
        SignatureBundle other = (SignatureBundle) o;
        return version == other.version && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, entries);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SignatureBundle{v=").append(version);
        for (Entry e : entries) {
            sb.append(" alg=").append(e.algorithm);
            sb.append(" len=").append(e.signature.length);
        }
        return sb.append("}").toString();
    }
}
