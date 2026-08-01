package net.bigtangle.crypto.pq;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Utils;
import net.bigtangle.params.TestParams;

/**
 * Suite-activation boundary tests for the "ML-DSA-87 now, dual later" design.
 *
 * <p>Genesis is ML-DSA-87 only. The dual suite (ML-DSA + SLH-DSA) becomes
 * mandatory only at/after its activation chain height; below it ML-DSA-only
 * proposer signatures are accepted, and after it ML-DSA-only proposer keys are
 * rejected (no downgrade).
 */
class SuiteActivationTest {

    private static final byte[] FILL_ML = new byte[32];
    private static final byte[] FILL_SLH = new byte[32];

    static {
        java.util.Arrays.fill(FILL_ML, (byte) 0x01);
        java.util.Arrays.fill(FILL_SLH, (byte) 0x02);
    }

    private static final byte[] SIGNING_HASH =
            Sha256Hash.hash("proposer-header".getBytes(StandardCharsets.UTF_8));

    private static PQSignatureProvider provider() {
        return new BcPQSignatureProvider();
    }

    /** Dual proposer key (ML-DSA-87 + SLH-DSA-SHA2-256s). */
    private static PQKey dualKey() {
        return PQKey.fromSeeds(FILL_ML, FILL_SLH);
    }

    /** ML-DSA-87 only proposer key. */
    private static PQKey mlOnlyKey() {
        return PQKey.fromMLDSA(FILL_ML);
    }

    /* ── Governance timeline ───────────────────────────────────────────── */

    @Test
    void genesisParamsAreMLDSAOnlyByDefault() {
        TestParams params = new TestParams();
        assertTrue(params.isPqSuiteActive(PQConstants.SUITE_ML_DSA_ONLY, 0));
        assertFalse(params.isPqSuiteActive(PQConstants.SUITE_ML_DSA_ONLY, -1));
        // Dual suite is not active by default: the chain starts ML-DSA-only.
        assertFalse(params.isPqSuiteActive(PQConstants.SUITE_CAT5_DUAL_1, 0));
        assertFalse(params.isPqSuiteActive(PQConstants.SUITE_CAT5_DUAL_1, Long.MAX_VALUE));
        assertEquals(-1L, params.getPqSuiteActivationHeight(PQConstants.SUITE_CAT5_DUAL_1));
    }

    @Test
    void suiteActivationBoundaryIsInclusive() {
        TestParams params = new TestParams();
        params.setPqSuiteActivationHeight(PQConstants.SUITE_CAT5_DUAL_1, 100_000L);
        assertFalse(params.isPqSuiteActive(PQConstants.SUITE_CAT5_DUAL_1, 99_999L));
        assertTrue(params.isPqSuiteActive(PQConstants.SUITE_CAT5_DUAL_1, 100_000L));
        assertTrue(params.isPqSuiteActive(PQConstants.SUITE_CAT5_DUAL_1, 100_001L));
        assertEquals(100_000L, params.getPqSuiteActivationHeight(PQConstants.SUITE_CAT5_DUAL_1));
        assertTrue(params.isPqSuiteActive(PQConstants.SUITE_CAT5_DUAL_1));
        params.removePqSuite(PQConstants.SUITE_CAT5_DUAL_1);
        assertFalse(params.isPqSuiteActive(PQConstants.SUITE_CAT5_DUAL_1));
    }

    @Test
    void genesisPubMatchesMLDSAOnlySeed() {
        PQKey expected = PQKey.fromMLDSA(FILL_ML);
        byte[] genesisPub = Utils.HEX.decode(TestParams.get().getGenesisPub());
        assertTrue(PQScriptUtils.isPQPubkey(genesisPub), "genesis must be a PQ pubkey");
        KeyBundle bundle = PQScriptUtils.extractKeyBundle(genesisPub);
        assertNull(bundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S),
                "genesis must not carry an SLH-DSA entry (ML-DSA-87 only)");
        assertNotNull(bundle.getEntry(PQConstants.ALG_ML_DSA_87));
        assertArrayEquals(expected.getKeyBundle().getEntry(PQConstants.ALG_ML_DSA_87).publicKey(),
                bundle.getEntry(PQConstants.ALG_ML_DSA_87).publicKey());
    }

    /* ── Proposer verification: ML-DSA-only phase ──────────────────────── */

    @Test
    void mldsaOnlyProposerAcceptedBeforeActivation() {
        PQKey key = mlOnlyKey();
        PQSignatureProvider p = provider();
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(SIGNING_HASH, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] mlSig = p.sign(PQConstants.ALG_ML_DSA_87, key.getMLDSAPrivateKey(), mlMsg);
        SignatureBundle sb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig)));

        assertTrue(PQScriptUtils.verifyProposerSignature(
                key.getKeyBundle(), sb, SIGNING_HASH, false),
                "ML-DSA-only proposer must pass while dual suite is inactive");
        assertFalse(PQScriptUtils.verifyProposerSignature(
                key.getKeyBundle(), sb, SIGNING_HASH, true),
                "ML-DSA-only proposer must be rejected once dual is required");
    }

    @Test
    void dualKeyProposerMaySignMLDSAOnlyBeforeActivation() {
        PQKey key = dualKey();
        PQSignatureProvider p = provider();
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(SIGNING_HASH, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] mlSig = p.sign(PQConstants.ALG_ML_DSA_87, key.getMLDSAPrivateKey(), mlMsg);
        SignatureBundle sb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig)));

        assertTrue(PQScriptUtils.verifyProposerSignature(
                key.getKeyBundle(), sb, SIGNING_HASH, false),
                "dual-seeded proposer signing ML-DSA only must pass pre-activation");
        assertFalse(PQScriptUtils.verifyProposerSignature(
                key.getKeyBundle(), sb, SIGNING_HASH, true),
                "same ML-DSA-only sig must fail once dual is required");
    }

    @Test
    void dualKeyProposerRequiresSlhAfterActivation() {
        PQKey key = dualKey();
        PQSignatureProvider p = provider();
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(SIGNING_HASH, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhMsg = PQScriptUtils.domainSeparatedHash(SIGNING_HASH, PQConstants.SLHDSA_SIG_DOMAIN);
        byte[] mlSig = p.sign(PQConstants.ALG_ML_DSA_87, key.getMLDSAPrivateKey(), mlMsg);
        byte[] slhSig = p.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, key.getSLHDSAPrivateKey(), slhMsg);
        SignatureBundle dualSb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig),
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig)));
        SignatureBundle mlOnlySb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig)));

        assertTrue(PQScriptUtils.verifyProposerSignature(key.getKeyBundle(), dualSb, SIGNING_HASH, true),
                "dual proposer with both sigs must pass after activation");
        assertTrue(PQScriptUtils.verifyProposerSignature(key.getKeyBundle(), dualSb, SIGNING_HASH, false),
                "and also pass in ML-DSA-only phase (both present, both checked)");
        assertFalse(PQScriptUtils.verifyProposerSignature(key.getKeyBundle(), mlOnlySb, SIGNING_HASH, true),
                "dual proposer dropping SLH-DSA must fail after activation (no downgrade)");
    }

    /* ── PQKey signing selector ────────────────────────────────────────── */

    @Test
    void pqKeySignSelectorControlsSlhDsa() {
        PQKey dual = dualKey();
        Sha256Hash input = Sha256Hash.wrap(SIGNING_HASH);

        SignatureBundle mlOnly = dual.sign(input, false);
        assertNotNull(mlOnly.getEntry(PQConstants.ALG_ML_DSA_87));
        assertNull(mlOnly.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S));

        SignatureBundle both = dual.sign(input, true);
        assertNotNull(both.getEntry(PQConstants.ALG_ML_DSA_87));
        assertNotNull(both.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S));

        assertNull(mlOnlyKey().sign(input, true).getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S),
                "ML-DSA-only key can never produce an SLH-DSA sig");
    }

    /* ── End-to-end: Block.verifyProposer gates on block height ────────── */

    private static Block proposerBlock(TestParams params, long height, PQKey key, boolean includeSlh) {
        Block b = Block.setBlock7(params, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH,
                BlockType.BLOCKTYPE_TRANSFER.name(), 0, 0);
        b.setHeight(height);
        b.setProposerKeyBundle(key.getKeyBundleBytes());
        byte[] signingHash = b.computeProposerSigningHash();
        PQSignatureProvider p = provider();
        List<SignatureBundle.Entry> entries = new ArrayList<>();
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(signingHash, PQConstants.MLDSA_SIG_DOMAIN);
        entries.add(new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87,
                p.sign(PQConstants.ALG_ML_DSA_87, key.getMLDSAPrivateKey(), mlMsg)));
        if (includeSlh) {
            byte[] slhMsg = PQScriptUtils.domainSeparatedHash(signingHash, PQConstants.SLHDSA_SIG_DOMAIN);
            entries.add(new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S,
                    p.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, key.getSLHDSAPrivateKey(), slhMsg)));
        }
        b.setProposerSignatureBundle(new SignatureBundle(entries).serialize());
        return b;
    }

    @Test
    void blockVerifiesProposerByHeight() {
        TestParams params = new TestParams();
        params.setPqSuiteActivationHeight(PQConstants.SUITE_CAT5_DUAL_1, 1000L);

        PQKey dual = dualKey();

        Block below = proposerBlock(params, 999, dual, false);
        assertTrue(below.verifyProposer(), "height 999 (pre-activation) accepts ML-DSA-only");

        Block at = proposerBlock(params, 1000, dual, false);
        assertFalse(at.verifyProposer(), "height 1000 (activation) rejects ML-DSA-only");

        Block after = proposerBlock(params, 1000, dual, true);
        assertTrue(after.verifyProposer(), "dual-signed block verifies at activation height");

        Block afterDown = proposerBlock(params, 1001, mlOnlyKey(), false);
        assertFalse(afterDown.verifyProposer(), "ML-DSA-only proposer key rejected after activation");
    }
}
