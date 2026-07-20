package net.bigtangle.crypto.pq;

/**
 * Post-quantum cryptography constants — algorithm identifiers, suite IDs,
 * domain separators, and key sizes defined in the PQ integration plan.
 *
 * <p>Algorithm identifiers are used in KeyBundle and SignatureBundle
 * entries.  Suite identifiers are used in addresses.  Domain separators
 * isolate ML-DSA and SLH-DSA sighash computations.
 */
public final class PQConstants {

    private PQConstants() {}

    /* ── Algorithm identifiers (uint8) ─────────────────────────────────── */

    /** ML-DSA-87 (FIPS 204, category 5, lattice-based). */
    public static final int ALG_ML_DSA_87 = 1;

    /** SLH-DSA-SHA2-256s (FIPS 205, category 5, hash-based). */
    public static final int ALG_SLH_DSA_SHA2_256S = 2;

    /* ── Suite identifiers ─────────────────────────────────────────────── */

    /** Dual ML-DSA-87 + SLH-DSA-SHA2-256s (category 5). */
    public static final int SUITE_CAT5_DUAL_1 = 1;

    /* ── Key sizes (bytes) ─────────────────────────────────────────────── */

    /** ML-DSA-87 public key size. */
    public static final int ML_DSA_87_PUBKEY_BYTES = 2560;

    /** ML-DSA-87 private key size (seed only). */
    public static final int ML_DSA_87_SEED_BYTES = 32;

    /** SLH-DSA-SHA2-256s public key size. */
    public static final int SLH_DSA_256S_PUBKEY_BYTES = 64;

    /** SLH-DSA-SHA2-256s private key size (seed only). */
    public static final int SLH_DSA_256S_SEED_BYTES = 32;

    /* ── Domain separators ─────────────────────────────────────────────── */

    /** Domain separator for ML-DSA-87 sighash computation. */
    public static final String MLDSA_SIG_DOMAIN = "MLDSA-SIG-DOMAIN";

    /** Domain separator for SLH-DSA-SHA2-256s sighash computation. */
    public static final String SLHDSA_SIG_DOMAIN = "SLHDSA-SIG-DOMAIN";

    /** Domain separator for transaction digest. */
    public static final String TX_DOMAIN = "BIGTANGLE-PQ-TX-v1";

    /** Domain separator for block merkle root. */
    public static final String MERKLE_DOMAIN = "BIGTANGLE-MERKLE-v1";

    /** HKDF salt for key derivation. */
    public static final String HKDF_SALT = "BIGTANGLE-PQ-v1";

    /** HKDF info for wallet root key derivation. */
    public static final String HKDF_INFO_WALLET = "wallet root";

    /* ── Address encoding ──────────────────────────────────────────────── */

    /** Current address version. */
    public static final int ADDRESS_VERSION = 1;

    /** Address hash length (SHA-256, 32 bytes). */
    public static final int ADDRESS_HASH_BYTES = 32;

    /** Network type: mainnet. */
    public static final int NETWORK_MAINNET = 0;

    /** Network type: testnet. */
    public static final int NETWORK_TESTNET = 1;

    /* ── Bundle encoding ───────────────────────────────────────────────── */

    /** Current KeyBundle / SignatureBundle version. */
    public static final int BUNDLE_VERSION = 1;

    /** Transaction version required for PQ fields (uint32 on wire). */
    public static final int TX_PQ_VERSION = 2;
}
