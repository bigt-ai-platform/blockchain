package net.bigtangle.crypto.pq;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;

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
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;

/**
 * Bouncy Castle implementation of {@link PQSignatureProvider}
 * supporting ML-DSA-87 and SLH-DSA-SHA2-256s.
 *
 * <p>Private key objects are cached in-memory by their encoded bytes.
 * Cold keys (loaded from disk) must be reconstructed via the full
 * encoded form.  Public keys are always reconstructable.
 */
public final class BcPQSignatureProvider implements PQSignatureProvider {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final int[] SUPPORTED = {
        PQConstants.ALG_ML_DSA_87,
        PQConstants.ALG_SLH_DSA_SHA2_256S
    };

    private final ConcurrentHashMap<ByteArrayWrapper, MLDSAPrivateKeyParameters>
            mldsaPrivCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ByteArrayWrapper, SLHDSAPrivateKeyParameters>
            slhdsaPrivCache = new ConcurrentHashMap<>();

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
        MLDSAKeyGenerationParameters genParams = new MLDSAKeyGenerationParameters(
                SECURE_RANDOM, params);
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
        MLDSAPrivateKeyParameters priv = mldsaPrivCache.get(new ByteArrayWrapper(privateKey));
        if (priv == null) {
            priv = new MLDSAPrivateKeyParameters(params, privateKey);
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
            return false;
        }
    }

    /* ── SLH-DSA-SHA2-256s ────────────────────────────────────────────── */

    private KeyPair generateSLHDSA(byte[] seed) {
        SLHDSAParameters params = SLHDSAParameters.sha2_256s;
        SecureRandom rng = new SecureRandom();
        rng.setSeed(seed);
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
        SLHDSAPrivateKeyParameters priv = slhdsaPrivCache.get(new ByteArrayWrapper(privateKey));
        if (priv == null) {
            priv = new SLHDSAPrivateKeyParameters(params, privateKey);
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
            return false;
        }
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
