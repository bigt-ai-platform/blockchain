package net.bigtangle.core;

/**
 * Key type discriminator used to dispatch signing/verification and address
 * derivation between legacy ECDSA/secp256k1 keys and post-quantum keys.
 */
public enum KeyType {
    /** Legacy ECDSA secp256k1 key (legacy {@code Address}, DER signature). */
    EC,

    /** Post-quantum key (ML-DSA-87 + SLH-DSA, {@code PQAddress}, SignatureBundle). */
    PQ;
}
