package net.bigtangle.crypto.pq;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * End-to-end dual-signature workflow: generate keys via HKDF,
 * sign with both ML-DSA-87 and SLH-DSA-SHA2-256s, verify both.
 */
class PQIntegrationTest {

    private final PQSignatureProvider provider = new BcPQSignatureProvider();

    @Test
    void fullDualSignatureWorkflow() {
        // 1. Derive key material from BIP39 seed
        byte[] seed = new byte[64];
        new SecureRandom().nextBytes(seed);
        byte[] keyMaterial = PQKeyDerivation.deriveRootKeyMaterial(seed);

        byte[] mlDsaSeed  = PQKeyDerivation.getMLDSASeed(keyMaterial);
        byte[] slhDsaSeed = PQKeyDerivation.getSLHDSASeed(keyMaterial);

        // 2. Generate key pairs
        PQSignatureProvider.KeyPair mlKp = provider.generateKeyPair(
                PQConstants.ALG_ML_DSA_87, mlDsaSeed);
        PQSignatureProvider.KeyPair slhKp = provider.generateKeyPair(
                PQConstants.ALG_SLH_DSA_SHA2_256S, slhDsaSeed);

        // 3. Build KeyBundle
        KeyBundle keyBundle = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, mlKp.publicKey()),
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.publicKey())));

        // 4. Create address
        PQAddress address = PQAddress.fromKeyBundle(
                PQConstants.NETWORK_TESTNET, PQConstants.SUITE_CAT5_DUAL_1, keyBundle);

        // 5. Sign a message with both algorithms (domain-separated)
        byte[] message = "hello post-quantum world".getBytes(StandardCharsets.UTF_8);
        byte[] txDigest = net.bigtangle.core.Sha256Hash.twiceOf(message).getBytes();

        byte[] mlSighash = domainSeparatedHash(txDigest, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhSighash = domainSeparatedHash(txDigest, PQConstants.SLHDSA_SIG_DOMAIN);

        byte[] mlSig = provider.sign(PQConstants.ALG_ML_DSA_87,
                mlKp.privateKey(), mlSighash);
        byte[] slhSig = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S,
                slhKp.privateKey(), slhSighash);

        // 6. Build SignatureBundle
        SignatureBundle sigBundle = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig),
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig)));

        // 7. Verify address match
        assertTrue(address.matches(keyBundle));

        // 8. Verify both signatures
        boolean mlValid = provider.verify(PQConstants.ALG_ML_DSA_87,
                keyBundle.getEntry(PQConstants.ALG_ML_DSA_87).publicKey(),
                mlSighash,
                sigBundle.getEntry(PQConstants.ALG_ML_DSA_87).signature());
        assertTrue(mlValid, "ML-DSA-87 signature must verify");

        boolean slhValid = provider.verify(PQConstants.ALG_SLH_DSA_SHA2_256S,
                keyBundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S).publicKey(),
                slhSighash,
                sigBundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S).signature());
        assertTrue(slhValid, "SLH-DSA-SHA2-256s signature must verify");
    }

    @Test
    void oneBadSignatureFailsButOtherSurvives() {
        byte[] seed = new byte[64];
        new SecureRandom().nextBytes(seed);
        byte[] keyMaterial = PQKeyDerivation.deriveRootKeyMaterial(seed);

        PQSignatureProvider.KeyPair mlKp = provider.generateKeyPair(
                PQConstants.ALG_ML_DSA_87, PQKeyDerivation.getMLDSASeed(keyMaterial));
        PQSignatureProvider.KeyPair slhKp = provider.generateKeyPair(
                PQConstants.ALG_SLH_DSA_SHA2_256S, PQKeyDerivation.getSLHDSASeed(keyMaterial));

        byte[] txDigest = net.bigtangle.core.Sha256Hash.twiceOf("test".getBytes(StandardCharsets.UTF_8)).getBytes();

        byte[] mlSighash = domainSeparatedHash(txDigest, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhSighash = domainSeparatedHash(txDigest, PQConstants.SLHDSA_SIG_DOMAIN);

        byte[] mlSig = provider.sign(PQConstants.ALG_ML_DSA_87, mlKp.privateKey(), mlSighash);
        byte[] slhSig = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.privateKey(), slhSighash);

        // Corrupt ML-DSA signature
        byte[] badMlSig = java.util.Arrays.copyOf(mlSig, mlSig.length);
        badMlSig[42] ^= (byte) 0xFF;

        boolean mlBad = provider.verify(PQConstants.ALG_ML_DSA_87,
                mlKp.publicKey(), mlSighash, badMlSig);
        assertFalse(mlBad, "corrupted ML-DSA sig must be rejected");

        // SLH-DSA should still be independently valid
        boolean slhGood = provider.verify(PQConstants.ALG_SLH_DSA_SHA2_256S,
                slhKp.publicKey(), slhSighash, slhSig);
        assertTrue(slhGood, "SLH-DSA sig must remain valid independently");
    }

    private static byte[] domainSeparatedHash(byte[] txDigest, String domain) {
        byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[domainBytes.length + txDigest.length];
        System.arraycopy(domainBytes, 0, combined, 0, domainBytes.length);
        System.arraycopy(txDigest, 0, combined, domainBytes.length, txDigest.length);
        return net.bigtangle.core.Sha256Hash.hash(combined);
    }
}
