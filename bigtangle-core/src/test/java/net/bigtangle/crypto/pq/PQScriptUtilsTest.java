package net.bigtangle.crypto.pq;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.bigtangle.core.Sha256Hash;

class PQScriptUtilsTest {

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
    void isPQPubkeyNullReturnsFalse() {
        assertFalse(PQScriptUtils.isPQPubkey(null));
    }

    @Test
    void isPQPubkeyTooShortReturnsFalse() {
        assertFalse(PQScriptUtils.isPQPubkey(new byte[]{PQScriptUtils.PQ_PUBKEY_PREFIX}));
    }

    @Test
    void isPQPubkeyValidReturnsTrue() {
        assertTrue(PQScriptUtils.isPQPubkey(prefixedPubkey));
    }

    @Test
    void prefixedPubkeyRoundTrip() {
        byte[] prefixed = PQScriptUtils.prefixedPubkey(keyBundle);
        assertTrue(prefixed[0] == PQScriptUtils.PQ_PUBKEY_PREFIX);
        KeyBundle extracted = PQScriptUtils.extractKeyBundle(prefixed);
        assertEquals(keyBundle, extracted);
    }

    @Test
    void verifyPQwithBothValidSignaturesReturnsTrue() {
        byte[] msg = Sha256Hash.hash("test-domain-sep".getBytes(StandardCharsets.UTF_8));
        Sha256Hash baseHash = Sha256Hash.twiceOf(msg);

        // Sign using the same domain-separated chain as verifyPQ uses internally:
        // baseHash -> TX_DOMAIN -> MLDSA_SIG_DOMAIN / SLHDSA_SIG_DOMAIN
        byte[] txHash = PQScriptUtils.domainSeparatedHash(baseHash.getBytes(), PQConstants.TX_DOMAIN);
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(txHash, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhMsg = PQScriptUtils.domainSeparatedHash(txHash, PQConstants.SLHDSA_SIG_DOMAIN);

        byte[] mlSig = provider.sign(PQConstants.ALG_ML_DSA_87, mlKp.privateKey(), mlMsg);
        byte[] slhSig = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.privateKey(), slhMsg);

        SignatureBundle sb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig),
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig)));

        assertTrue(PQScriptUtils.verifyPQ(prefixedPubkey, sb.serialize(), baseHash));
    }

    @Test
    void verifyPQwithBadSignatureReturnsFalse() {
        byte[] msg = Sha256Hash.hash("bad-test".getBytes(StandardCharsets.UTF_8));
        Sha256Hash baseHash = Sha256Hash.twiceOf(msg);

        byte[] txHash = PQScriptUtils.domainSeparatedHash(baseHash.getBytes(), PQConstants.TX_DOMAIN);
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(txHash, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhMsg = PQScriptUtils.domainSeparatedHash(txHash, PQConstants.SLHDSA_SIG_DOMAIN);

        byte[] mlSig = provider.sign(PQConstants.ALG_ML_DSA_87, mlKp.privateKey(), mlMsg);
        byte[] slhSig = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.privateKey(), slhMsg);

        byte[] badMlSig = java.util.Arrays.copyOf(mlSig, mlSig.length);
        badMlSig[badMlSig.length / 2] ^= (byte) 0xFF;

        SignatureBundle sb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, badMlSig),
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig)));

        assertFalse(PQScriptUtils.verifyPQ(prefixedPubkey, sb.serialize(), baseHash));
    }

    @Test
    void verifyPQwithMissingEntryReturnsFalse() {
        byte[] msg = Sha256Hash.hash("missing-entry".getBytes(StandardCharsets.UTF_8));
        Sha256Hash baseHash = Sha256Hash.twiceOf(msg);

        // Only ML-DSA sig, no SLH-DSA
        byte[] txHash = PQScriptUtils.domainSeparatedHash(baseHash.getBytes(), PQConstants.TX_DOMAIN);
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(txHash, PQConstants.MLDSA_SIG_DOMAIN);

        byte[] mlSig = provider.sign(PQConstants.ALG_ML_DSA_87, mlKp.privateKey(), mlMsg);

        SignatureBundle sb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig)));

        assertFalse(PQScriptUtils.verifyPQ(prefixedPubkey, sb.serialize(), baseHash));
    }

    @Test
    void verifyProposerSignatureValidReturnsTrue() {
        byte[] signingHash = Sha256Hash.hash("block-header-hash".getBytes(StandardCharsets.UTF_8));

        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(signingHash, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhMsg = PQScriptUtils.domainSeparatedHash(signingHash, PQConstants.SLHDSA_SIG_DOMAIN);

        byte[] mlSig = provider.sign(PQConstants.ALG_ML_DSA_87, mlKp.privateKey(), mlMsg);
        byte[] slhSig = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.privateKey(), slhMsg);

        SignatureBundle sb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig),
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig)));

        assertTrue(PQScriptUtils.verifyProposerSignature(keyBundle, sb, signingHash));
    }

    @Test
    void verifyProposerSignatureBadReturnsFalse() {
        byte[] signingHash = Sha256Hash.hash("wrong-block".getBytes(StandardCharsets.UTF_8));
        byte[] wrongHash = Sha256Hash.hash("different".getBytes(StandardCharsets.UTF_8));

        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(wrongHash, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhMsg = PQScriptUtils.domainSeparatedHash(wrongHash, PQConstants.SLHDSA_SIG_DOMAIN);

        byte[] mlSig = provider.sign(PQConstants.ALG_ML_DSA_87, mlKp.privateKey(), mlMsg);
        byte[] slhSig = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.privateKey(), slhMsg);

        SignatureBundle sb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig),
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig)));

        assertFalse(PQScriptUtils.verifyProposerSignature(keyBundle, sb, signingHash));
    }

    @Test
    void domainSeparatedHashOverloadsConsistent() {
        byte[] msg = Sha256Hash.hash("consistency".getBytes(StandardCharsets.UTF_8));
        byte[] domain = "TEST-DOMAIN".getBytes(StandardCharsets.UTF_8);
        String domainStr = "TEST-DOMAIN";

        byte[] fromSha = PQScriptUtils.domainSeparatedHash(Sha256Hash.wrap(msg), domainStr);
        byte[] fromRaw = PQScriptUtils.domainSeparatedHash(msg, domainStr);

        assertArrayEquals(fromSha, fromRaw);
    }

    @Test
    void verifyPQwithWrongDomainSeparatorFails() {
        byte[] msg = Sha256Hash.hash("wrong-domain".getBytes(StandardCharsets.UTF_8));
        Sha256Hash baseHash = Sha256Hash.twiceOf(msg);

        // Sign with TX_DOMAIN + MLDSA_SIG_DOMAIN (correct for verifyPQ)
        byte[] txHash = PQScriptUtils.domainSeparatedHash(baseHash.getBytes(), PQConstants.TX_DOMAIN);
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(txHash, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhMsg = PQScriptUtils.domainSeparatedHash(txHash, PQConstants.SLHDSA_SIG_DOMAIN);

        byte[] mlSig = provider.sign(PQConstants.ALG_ML_DSA_87, mlKp.privateKey(), mlMsg);
        byte[] slhSig = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.privateKey(), slhMsg);

        // Tamper with the TX domain prefix in the signature (wrong message)
        byte[] wrongTxHash = PQScriptUtils.domainSeparatedHash(baseHash.getBytes(), "WRONG-DOMAIN-v1");
        byte[] wrongMlMsg = PQScriptUtils.domainSeparatedHash(wrongTxHash, PQConstants.MLDSA_SIG_DOMAIN);

        byte[] wrongMlSig = provider.sign(PQConstants.ALG_ML_DSA_87, mlKp.privateKey(), wrongMlMsg);

        SignatureBundle sb = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, wrongMlSig),
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig)));

        assertFalse(PQScriptUtils.verifyPQ(prefixedPubkey, sb.serialize(), baseHash));
    }
}
