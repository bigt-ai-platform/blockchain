package net.bigtangle.crypto.pq;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Utils;

/**
 * Post-quantum address: 35 bytes (1 version + 1 network + 1 suite + 32 hash).
 *
 * <p>The address commits to the canonical encoding of a KeyBundle via SHA-256.
 * This is a dedicated PQ address type — not compatible with legacy ECDSA addresses.
 */
public final class PQAddress {

    private final int version;
    private final int network;
    private final int suite;
    private final byte[] hash;   // 32 bytes, SHA-256 of KeyBundle

    public PQAddress(int version, int network, int suite, byte[] hash) {
        if (hash.length != PQConstants.ADDRESS_HASH_BYTES)
            throw new IllegalArgumentException("hash must be 32 bytes");
        this.version = version;
        this.network = network;
        this.suite = suite;
        this.hash = hash.clone();
    }

    /** Create address from a KeyBundle. */
    public static PQAddress fromKeyBundle(int network, int suite, KeyBundle keyBundle) {
        byte[] bundleBytes = keyBundle.serialize();
        byte[] hash = Sha256Hash.hash(bundleBytes);
        return new PQAddress(PQConstants.ADDRESS_VERSION, network, suite, hash);
    }

    /** Create address from a pre-serialized KeyBundle. */
    public static PQAddress fromSerializedBundle(int network, int suite, byte[] bundleBytes) {
        byte[] hash = Sha256Hash.hash(bundleBytes);
        return new PQAddress(PQConstants.ADDRESS_VERSION, network, suite, hash);
    }

    public int version() { return version; }
    public int network() { return network; }
    public int suite() { return suite; }
    public byte[] hash() { return hash.clone(); }

    /** Verify that a given KeyBundle matches this address. */
    public boolean matches(KeyBundle keyBundle) {
        byte[] bundleBytes = keyBundle.serialize();
        byte[] computed = Sha256Hash.hash(bundleBytes);
        return java.util.Arrays.equals(hash, computed);
    }

    /** Serialize to 35 bytes for on-chain storage. */
    public byte[] serialize() {
        byte[] result = new byte[35];
        result[0] = (byte) version;
        result[1] = (byte) network;
        result[2] = (byte) suite;
        System.arraycopy(hash, 0, result, 3, 32);
        return result;
    }

    /** Deserialize from 35 bytes. */
    public static PQAddress deserialize(byte[] bytes) {
        if (bytes.length != 35) throw new IllegalArgumentException("address must be 35 bytes");
        int version = bytes[0] & 0xFF;
        int network = bytes[1] & 0xFF;
        int suite = bytes[2] & 0xFF;
        byte[] hash = java.util.Arrays.copyOfRange(bytes, 3, 35);
        return new PQAddress(version, network, suite, hash);
    }

    /** Encode as hex string. */
    public String toHex() {
        return Utils.HEX.encode(serialize());
    }

    /** Decode from hex string. */
    public static PQAddress fromHex(String hex) {
        return deserialize(Utils.HEX.decode(hex));
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PQAddress)) return false;
        PQAddress a = (PQAddress) o;
        return version == a.version && network == a.network
                && suite == a.suite && java.util.Arrays.equals(hash, a.hash);
    }

    @Override
    public int hashCode() {
        int result = version;
        result = 31 * result + network;
        result = 31 * result + suite;
        result = 31 * result + java.util.Arrays.hashCode(hash);
        return result;
    }

    @Override
    public String toString() {
        return "PQAddress{v=" + version + " n=" + network + " s=" + suite
                + " h=" + Utils.HEX.encode(hash).substring(0, 8) + "...}";
    }
}
