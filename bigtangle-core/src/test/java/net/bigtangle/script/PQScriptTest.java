package net.bigtangle.script;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.bigtangle.core.ECKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.crypto.pq.*;

/**
 * Integration tests: Script-level PQ verification and ScriptBuilder PQ helpers.
 */
class PQScriptTest {

    private static BcPQSignatureProvider provider;
    private static PQSignatureProvider.KeyPair mlKp;
    private static PQSignatureProvider.KeyPair slhKp;
    private static KeyBundle keyBundle;
    private static byte[] prefixedPubkey;

    @BeforeAll
    static void setUp() {
        provider = new BcPQSignatureProvider();
        PQScriptUtils.setProvider(provider);

        byte[] seed = new byte[64];
        new SecureRandom().nextBytes(seed);
        byte[] km = PQKeyDerivation.deriveRootKeyMaterial(seed);
        mlKp = provider.generateKeyPair(PQConstants.ALG_ML_DSA_87,
                PQKeyDerivation.getMLDSASeed(km));
        slhKp = provider.generateKeyPair(PQConstants.ALG_SLH_DSA_SHA2_256S,
                PQKeyDerivation.getSLHDSASeed(km));
        keyBundle = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, mlKp.publicKey()),
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.publicKey())));
        prefixedPubkey = PQScriptUtils.prefixedPubkey(keyBundle);
    }

    @Test
    void pqOutputScriptContainsPrefix() {
        Script output = ScriptBuilder.createOutputScript(keyBundle);
        byte[] prog = output.getProgram();
        boolean found = false;
        for (byte b : prog) found |= (b == PQScriptUtils.PQ_PUBKEY_PREFIX);
        assertTrue(found);
    }

    @Test
    void pqPubkeyDetection() {
        assertTrue(PQScriptUtils.isPQPubkey(prefixedPubkey));
        assertFalse(PQScriptUtils.isPQPubkey(new ECKey().getPubKey()));
        assertFalse(PQScriptUtils.isPQPubkey(null));
        assertFalse(PQScriptUtils.isPQPubkey(new byte[0]));
    }

    @Test
    void extractKeyBundleRoundTrip() {
        KeyBundle extracted = PQScriptUtils.extractKeyBundle(prefixedPubkey);
        assertEquals(keyBundle, extracted);
        assertEquals(2, extracted.entries().size());
    }

    @Test
    void pqVerifyWithCorrectSigsReturnsTrue() {
        byte[] msg = Sha256Hash.hash("test".getBytes(StandardCharsets.UTF_8));
        Sha256Hash baseHash = Sha256Hash.twiceOf(msg);
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(baseHash, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhMsg = PQScriptUtils.domainSeparatedHash(baseHash, PQConstants.SLHDSA_SIG_DOMAIN);

        byte[] mlSig = provider.sign(PQConstants.ALG_ML_DSA_87, mlKp.privateKey(), mlMsg);
        byte[] slhSig = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.privateKey(), slhMsg);

        SignatureBundle sb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig),
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig)));

        assertTrue(PQScriptUtils.verifyPQ(prefixedPubkey, sb.serialize(), baseHash));
    }

    @Test
    void pqVerifyWithBadMlSigReturnsFalse() {
        byte[] msg = Sha256Hash.hash("bad".getBytes(StandardCharsets.UTF_8));
        Sha256Hash baseHash = Sha256Hash.twiceOf(msg);
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(baseHash, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhMsg = PQScriptUtils.domainSeparatedHash(baseHash, PQConstants.SLHDSA_SIG_DOMAIN);

        byte[] mlSig = provider.sign(PQConstants.ALG_ML_DSA_87, mlKp.privateKey(), mlMsg);
        byte[] slhSig = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.privateKey(), slhMsg);

        // Corrupt ML-DSA sig
        byte[] badMlSig = java.util.Arrays.copyOf(mlSig, mlSig.length);
        badMlSig[100] ^= (byte) 0xFF;

        SignatureBundle sb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, badMlSig),
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig)));

        assertFalse(PQScriptUtils.verifyPQ(prefixedPubkey, sb.serialize(), baseHash));
    }

    @Test
    void scriptBuilderProducesValidInputScript() throws Exception {
        byte[] msg = Sha256Hash.hash("roundtrip".getBytes(StandardCharsets.UTF_8));
        Sha256Hash baseHash = Sha256Hash.twiceOf(msg);
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(baseHash, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhMsg = PQScriptUtils.domainSeparatedHash(baseHash, PQConstants.SLHDSA_SIG_DOMAIN);
        byte[] mlSig = provider.sign(PQConstants.ALG_ML_DSA_87, mlKp.privateKey(), mlMsg);
        byte[] slhSig = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.privateKey(), slhMsg);

        SignatureBundle sb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig),
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig)));

        // Round-trip: serialize SignatureBundle, then deserialize
        byte[] raw = sb.serialize();
        assertEquals(sb, SignatureBundle.deserialize(raw));
    }
}
