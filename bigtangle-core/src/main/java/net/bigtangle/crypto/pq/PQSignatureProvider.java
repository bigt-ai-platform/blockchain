package net.bigtangle.crypto.pq;

/**
 * Abstract provider for post-quantum signatures.
 * Consensus code depends only on this interface, never on a specific
 * cryptographic library.
 */
public interface PQSignatureProvider {

    /**
     * Generate a key pair for the given algorithm.
     *
     * @param algorithm one of PQConstants.ALG_ML_DSA_87 / ALG_SLH_DSA_SHA2_256S
     * @param seed      32 bytes of entropy for deterministic generation
     * @return a new KeyPair with both public and private key bytes
     */
    KeyPair generateKeyPair(int algorithm, byte[] seed);

    /**
     * Sign a message hash.
     *
     * @param algorithm  one of the ALG_* constants
     * @param privateKey raw private key bytes (as returned by generateKeyPair)
     * @param message    the message hash to sign
     * @return raw signature bytes
     */
    byte[] sign(int algorithm, byte[] privateKey, byte[] message);

    /**
     * Verify a signature.
     */
    boolean verify(int algorithm, byte[] publicKey, byte[] message, byte[] signature);

    int[] supportedAlgorithms();

    final class KeyPair {
        private final int algorithm;
        private final byte[] publicKey;
        private final byte[] privateKey;

        public KeyPair(int algorithm, byte[] publicKey, byte[] privateKey) {
            this.algorithm = algorithm;
            this.publicKey = publicKey.clone();
            this.privateKey = privateKey.clone();
        }

        public int algorithm() { return algorithm; }
        public byte[] publicKey() { return publicKey.clone(); }
        public byte[] privateKey() { return privateKey.clone(); }
    }
}
