package net.bigtangle.crypto.pq;

import java.security.Provider;
import java.security.SecureRandom;
import java.security.SecureRandomSpi;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.prng.DigestRandomGenerator;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner;
import org.bouncycastle.pqc.crypto.slhdsa.SLHDSAParameters;
import org.bouncycastle.pqc.crypto.slhdsa.SLHDSAPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.slhdsa.SLHDSAPublicKeyParameters;
import org.bouncycastle.pqc.crypto.slhdsa.SLHDSASigner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bouncy Castle implementation of {@link PQSignatureProvider}
 * supporting ML-DSA-87 and SLH-DSA-SHA2-256s.
 *
 * <p>Private key objects are cached in-memory by their encoded bytes.
 * Cold keys (loaded from disk) must be reconstructed via the full
 * encoded form.  Public keys are always reconstructable.
 */
public final class BcPQSignatureProvider implements PQSignatureProvider {

    private static final Logger log = LoggerFactory.getLogger(BcPQSignatureProvider.class);

    private static final int[] SUPPORTED = {
        PQConstants.ALG_ML_DSA_87,
        PQConstants.ALG_SLH_DSA_SHA2_256S
    };

    private final Cache<ByteArrayWrapper, MLDSAPrivateKeyParameters>
            mldsaPrivCache = CacheBuilder.newBuilder().maximumSize(1000).build();
    private final Cache<ByteArrayWrapper, SLHDSAPrivateKeyParameters>
            slhdsaPrivCache = CacheBuilder.newBuilder().maximumSize(1000).build();

    @Override
    public int[] supportedAlgorithms() {
        return SUPPORTED.clone();
    }

    @Override
    public KeyPair generateKeyPair(int algorithm, byte[] seed) {
        switch (algorithm) {
            case PQConstants.ALG_ML_DSA_87:
                return generateMLDSA(seed);
            case PQConstants.ALG_SLH_DSA_SHA2_256S:
                return generateSLHDSA(seed);
            default:
                throw new UnsupportedOperationException("unknown algorithm: " + algorithm);
        }
    }

    @Override
    public byte[] sign(int algorithm, byte[] privateKey, byte[] message) {
        switch (algorithm) {
            case PQConstants.ALG_ML_DSA_87:
                return signMLDSA(privateKey, message);
            case PQConstants.ALG_SLH_DSA_SHA2_256S:
                return signSLHDSA(privateKey, message);
            default:
                throw new UnsupportedOperationException("unknown algorithm: " + algorithm);
        }
    }

    @Override
    public boolean verify(int algorithm, byte[] publicKey, byte[] message, byte[] signature) {
        switch (algorithm) {
            case PQConstants.ALG_ML_DSA_87:
                return verifyMLDSA(publicKey, message, signature);
            case PQConstants.ALG_SLH_DSA_SHA2_256S:
                return verifySLHDSA(publicKey, message, signature);
            default:
                throw new UnsupportedOperationException("unknown algorithm: " + algorithm);
        }
    }

    /* ── ML-DSA-87 ────────────────────────────────────────────────────── */

    private KeyPair generateMLDSA(byte[] seed) {
        MLDSAParameters params = MLDSAParameters.ml_dsa_87;
        SecureRandom rng = deterministicRng(seed);
        MLDSAKeyGenerationParameters genParams = new MLDSAKeyGenerationParameters(rng, params);
        MLDSAKeyPairGenerator generator = new MLDSAKeyPairGenerator();
        generator.init(genParams);
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        MLDSAPrivateKeyParameters priv = (MLDSAPrivateKeyParameters) pair.getPrivate();
        MLDSAPublicKeyParameters pub = (MLDSAPublicKeyParameters) pair.getPublic();
        byte[] pubEncoded = pub.getEncoded();
        byte[] privEncoded = priv.getEncoded();
        mldsaPrivCache.put(new ByteArrayWrapper(privEncoded), priv);
        return new KeyPair(PQConstants.ALG_ML_DSA_87, pubEncoded, privEncoded);
    }

    private byte[] signMLDSA(byte[] privateKey, byte[] message) {
        MLDSAParameters params = MLDSAParameters.ml_dsa_87;
        ByteArrayWrapper key = new ByteArrayWrapper(privateKey);
        MLDSAPrivateKeyParameters priv = mldsaPrivCache.getIfPresent(key);
        if (priv == null) {
            priv = new MLDSAPrivateKeyParameters(params, privateKey);
            mldsaPrivCache.put(key, priv);
        }
        MLDSASigner signer = new MLDSASigner();
        signer.init(true, priv);
        signer.update(message, 0, message.length);
        try {
            return signer.generateSignature();
        } catch (Exception e) {
            throw new RuntimeException("ML-DSA sign failed: " + e.getMessage(), e);
        }
    }

    private boolean verifyMLDSA(byte[] publicKey, byte[] message, byte[] signature) {
        try {
            MLDSAParameters params = MLDSAParameters.ml_dsa_87;
            MLDSAPublicKeyParameters pub = new MLDSAPublicKeyParameters(params, publicKey);
            MLDSASigner signer = new MLDSASigner();
            signer.init(false, pub);
            signer.update(message, 0, message.length);
            return signer.verifySignature(signature);
        } catch (Exception e) {
            log.debug("ML-DSA-87 verify failed: {}", e.getMessage());
            return false;
        }
    }

    /* ── SLH-DSA-SHA2-256s ────────────────────────────────────────────── */

    private KeyPair generateSLHDSA(byte[] seed) {
        SLHDSAParameters params = SLHDSAParameters.sha2_256s;
        SecureRandom rng = deterministicRng(seed);
        org.bouncycastle.pqc.crypto.slhdsa.SLHDSAKeyGenerationParameters genParams =
            new org.bouncycastle.pqc.crypto.slhdsa.SLHDSAKeyGenerationParameters(rng, params);
        org.bouncycastle.pqc.crypto.slhdsa.SLHDSAKeyPairGenerator generator =
            new org.bouncycastle.pqc.crypto.slhdsa.SLHDSAKeyPairGenerator();
        generator.init(genParams);
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        SLHDSAPrivateKeyParameters priv = (SLHDSAPrivateKeyParameters) pair.getPrivate();
        SLHDSAPublicKeyParameters pub = (SLHDSAPublicKeyParameters) pair.getPublic();
        byte[] pubEncoded = pub.getEncoded();
        byte[] privEncoded = priv.getEncoded();
        slhdsaPrivCache.put(new ByteArrayWrapper(privEncoded), priv);
        return new KeyPair(PQConstants.ALG_SLH_DSA_SHA2_256S, pubEncoded, privEncoded);
    }

    private byte[] signSLHDSA(byte[] privateKey, byte[] message) {
        SLHDSAParameters params = SLHDSAParameters.sha2_256s;
        ByteArrayWrapper key = new ByteArrayWrapper(privateKey);
        SLHDSAPrivateKeyParameters priv = slhdsaPrivCache.getIfPresent(key);
        if (priv == null) {
            priv = new SLHDSAPrivateKeyParameters(params, privateKey);
            slhdsaPrivCache.put(key, priv);
        }
        SLHDSASigner signer = new SLHDSASigner();
        signer.init(true, priv);
        return signer.generateSignature(message);
    }

    private boolean verifySLHDSA(byte[] publicKey, byte[] message, byte[] signature) {
        try {
            SLHDSAParameters params = SLHDSAParameters.sha2_256s;
            SLHDSAPublicKeyParameters pub = new SLHDSAPublicKeyParameters(params, publicKey);
            SLHDSASigner signer = new SLHDSASigner();
            signer.init(false, pub);
            return signer.verifySignature(message, signature);
        } catch (Exception e) {
            log.debug("SLH-DSA-256s verify failed: {}", e.getMessage());
            return false;
        }
    }

    /* ── Deterministic RNG for seed-based key generation ──────────────── */

    private static final class DetRng extends SecureRandom {
        DetRng(SecureRandomSpi spi, Provider p) { super(spi, p); }
    }

    private static SecureRandom deterministicRng(byte[] seed) {
        byte[] seedCopy = seed.clone();
        DigestRandomGenerator drg = new DigestRandomGenerator(new SHA256Digest());
        drg.addSeedMaterial(seedCopy);
        SecureRandomSpi spi = new SecureRandomSpi() {
            @Override protected void engineSetSeed(byte[] s) { drg.addSeedMaterial(s); }
            @Override protected void engineNextBytes(byte[] bytes) { drg.nextBytes(bytes); }
            @Override protected byte[] engineGenerateSeed(int n) {
                byte[] b = new byte[n]; drg.nextBytes(b); return b;
            }
        };
        return new DetRng(spi, new Provider("Drg", 1.0, "") {});
    }

    /* ── Internal key cache ───────────────────────────────────────────── */

    private static final class ByteArrayWrapper {
        final byte[] data;
        ByteArrayWrapper(byte[] data) { this.data = data; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof ByteArrayWrapper)) return false;
            return java.util.Arrays.equals(data, ((ByteArrayWrapper) o).data);
        }
        @Override public int hashCode() { return java.util.Arrays.hashCode(data); }
    }
}
