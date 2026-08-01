package net.bigtangle.core;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import jakarta.annotation.Nullable;

import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.ECPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.crypto.EncryptableItem;
import net.bigtangle.crypto.EncryptedData;
import net.bigtangle.crypto.KeyCrypter;
import net.bigtangle.crypto.KeyCrypterException;
import net.bigtangle.crypto.EncryptionType;
import net.bigtangle.crypto.pq.BcPQSignatureProvider;
import net.bigtangle.crypto.pq.KeyBundle;
import net.bigtangle.crypto.pq.PQAddress;
import net.bigtangle.crypto.pq.PQConstants;
import net.bigtangle.crypto.pq.PQKeyDerivation;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.crypto.pq.PQSignatureProvider;
import net.bigtangle.crypto.pq.SignatureBundle;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;

public class PQKey implements EncryptableItem {

    private static final Logger log = LoggerFactory.getLogger(PQKey.class);
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final PQSignatureProvider prov = new BcPQSignatureProvider();

    static {
        PQScriptUtils.setProvider(prov);
    }

    public static PQSignatureProvider provider() { return prov; }

    private final byte[] mlDsaPrivateKey;
    private final byte[] slhDsaPrivateKey;
    private final KeyBundle keyBundle;
    @Nullable private PQAddress address;

    protected long creationTimeSeconds;
    protected KeyCrypter keyCrypter;
    protected EncryptedData encryptedPrivateKey;

    public PQKey(byte[] mlDsaPrivateKey, byte[] slhDsaPrivateKey, KeyBundle keyBundle) {
        this.mlDsaPrivateKey = mlDsaPrivateKey;
        this.slhDsaPrivateKey = slhDsaPrivateKey;
        this.keyBundle = keyBundle;
        this.creationTimeSeconds = Utils.currentTimeSeconds();
    }

    protected PQKey() {
        this.mlDsaPrivateKey = null;
        this.slhDsaPrivateKey = null;
        this.keyBundle = null;
    }

    // Default: ML-DSA-87 only (FIPS 204).  Dual (SLH-DSA) keys are created
    // explicitly via fromSeeds()/fromPrivateKeyHex(64-byte) and only sign
    // SLH-DSA once the dual suite is active at the block height.
    public static PQKey createNew() {
        byte[] seed = new byte[32];
        secureRandom.nextBytes(seed);
        return fromMLDSA(seed);
    }

    public static PQKey fromPrivateKeyHex(String hex) {
        byte[] seed = Utils.HEX.decode(hex);
        if (seed.length == 64)
            return fromSeeds(Arrays.copyOfRange(seed, 0, 32), Arrays.copyOfRange(seed, 32, 64));
        if (seed.length == 32)
            return fromMLDSA(seed);
        throw new IllegalArgumentException("Expected 64 or 128 hex chars (32-byte ML-DSA-only or 64-byte dual seed), got " + hex.length());
    }

    public static PQKey createNewMLDSA() {
        byte[] seed = new byte[32];
        secureRandom.nextBytes(seed);
        return fromMLDSA(seed);
    }

    public static PQKey fromSeeds(byte[] mlDsaSeed, byte[] slhDsaSeed) {
        PQSignatureProvider.KeyPair mlKp = prov.generateKeyPair(PQConstants.ALG_ML_DSA_87, mlDsaSeed);
        PQSignatureProvider.KeyPair slhKp = prov.generateKeyPair(PQConstants.ALG_SLH_DSA_SHA2_256S, slhDsaSeed);
        List<KeyBundle.Entry> entries = new ArrayList<>();
        entries.add(new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, mlKp.publicKey()));
        entries.add(new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKp.publicKey()));
        KeyBundle bundle = new KeyBundle(entries);
        return new PQKey(mlKp.privateKey(), slhKp.privateKey(), bundle);
    }

    public static PQKey fromMLDSA(byte[] mlDsaSeed) {
        PQSignatureProvider.KeyPair mlKp = prov.generateKeyPair(PQConstants.ALG_ML_DSA_87, mlDsaSeed);
        List<KeyBundle.Entry> entries = new ArrayList<>();
        entries.add(new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, mlKp.publicKey()));
        KeyBundle bundle = new KeyBundle(entries);
        return new PQKey(mlKp.privateKey(), null, bundle);
    }

    public static PQKey fromPrivateKeyBundle(byte[] mlDsaPriv, byte[] slhDsaPriv, KeyBundle pubKeyBundle) {
        return new PQKey(mlDsaPriv, slhDsaPriv, pubKeyBundle);
    }

    public PQKey(KeyBundle pubKeyBundle) {
        this(null, null, pubKeyBundle);
    }

    public static PQKey fromPublicOnly(KeyBundle pubKeyBundle) {
        return new PQKey(null, null, pubKeyBundle);
    }

    public static PQKey fromPublicOnly(byte[] prefixedPubkey) {
        KeyBundle bundle = PQScriptUtils.extractKeyBundle(prefixedPubkey);
        return fromPublicOnly(bundle);
    }

    public static PQKey fromPublicOnlyBytes(byte[] keyBundleBytes) {
        return fromPublicOnly(KeyBundle.deserialize(keyBundleBytes));
    }

    public boolean hasPrivateKey() {
        return mlDsaPrivateKey != null;
    }

    public KeyBundle getKeyBundle() { return keyBundle; }

    public byte[] getPublicKeyBytes() {
        return PQScriptUtils.prefixedPubkey(keyBundle);
    }

    public byte[] getKeyBundleBytes() {
        return keyBundle.serialize();
    }

    @Nullable
    public byte[] getMLDSAPrivateKey() { return mlDsaPrivateKey; }

    @Nullable
    public byte[] getSLHDSAPrivateKey() { return slhDsaPrivateKey; }

    public SignatureBundle sign(Sha256Hash input) {
        return sign(input, true);
    }

    /**
     * Sign a hash with ML-DSA-87 always, and additionally with SLH-DSA-SHA2-256s
     * when {@code includeSlhDsa} is true and this key holds an SLH-DSA private key.
     * Transactions should keep the default (all algorithms the key holds); the
     * proposer path passes {@code false} while the dual suite is inactive.
     */
    public SignatureBundle sign(Sha256Hash input, boolean includeSlhDsa) {
        if (mlDsaPrivateKey == null)
            throw new MissingPrivateKeyException();
        byte[] txHash = PQScriptUtils.domainSeparatedHash(input.getBytes(), PQConstants.TX_DOMAIN);
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(txHash, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] mlSig = prov.sign(PQConstants.ALG_ML_DSA_87, mlDsaPrivateKey, mlMsg);
        List<SignatureBundle.Entry> entries = new ArrayList<>();
        entries.add(new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig));
        if (includeSlhDsa && slhDsaPrivateKey != null) {
            byte[] slhMsg = PQScriptUtils.domainSeparatedHash(txHash, PQConstants.SLHDSA_SIG_DOMAIN);
            byte[] slhSig = prov.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, slhDsaPrivateKey, slhMsg);
            entries.add(new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig));
        }
        return new SignatureBundle(entries);
    }

    public PQAddress toAddress(int network) {
        if (address == null) {
            int suite = keyBundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S) != null
                    ? PQConstants.SUITE_CAT5_DUAL_1 : PQConstants.SUITE_ML_DSA_ONLY;
            address = PQAddress.fromKeyBundle(network, suite, keyBundle);
        }
        return address;
    }

    public PQAddress toAddress(NetworkParameters params) {
        return toAddress(PQConstants.NETWORK_TESTNET);
    }

    public byte[] getPubKey() { return getPublicKeyBytes(); }

    public String getPublicKeyAsHex() {
        return Utils.HEX.encode(getPublicKeyBytes());
    }

    @Nullable
    public String getPrivateKeyMlAsHex() {
        return mlDsaPrivateKey != null ? Utils.HEX.encode(mlDsaPrivateKey) : null;
    }

    @Nullable
    public String getPrivateKeySlhAsHex() {
        return slhDsaPrivateKey != null ? Utils.HEX.encode(slhDsaPrivateKey) : null;
    }

    @Override
    public long getCreationTimeSeconds() { return creationTimeSeconds; }

    public void setCreationTimeSeconds(long t) { creationTimeSeconds = t; }

    public PQKey encrypt(KeyCrypter keyCrypter, KeyParameter aesKey) throws KeyCrypterException {
        byte[] secret = encodePrivateKeys();
        EncryptedData enc = keyCrypter.encrypt(secret, aesKey);
        PQKey result = fromPublicOnly(keyBundle);
        result.encryptedPrivateKey = enc;
        result.keyCrypter = keyCrypter;
        result.creationTimeSeconds = creationTimeSeconds;
        return result;
    }

    public PQKey decrypt(KeyCrypter keyCrypter, KeyParameter aesKey) throws KeyCrypterException {
        if (encryptedPrivateKey == null)
            throw new KeyCrypterException("Key is not encrypted");
        byte[] decrypted = keyCrypter.decrypt(encryptedPrivateKey, aesKey);
        PQKey decryptedKey = decodePrivateKeys(decrypted, keyBundle);
        decryptedKey.creationTimeSeconds = creationTimeSeconds;
        return decryptedKey;
    }

    public PQKey decrypt(KeyParameter aesKey) throws KeyCrypterException {
        if (keyCrypter == null)
            throw new KeyCrypterException("No key crypter available");
        return decrypt(keyCrypter, aesKey);
    }

    @Override
    public boolean isEncrypted() {
        return encryptedPrivateKey != null && encryptedPrivateKey.encryptedBytes.length > 0;
    }

    @Nullable
    @Override
    public byte[] getSecretBytes() {
        return hasPrivateKey() ? encodePrivateKeys() : null;
    }

    @Nullable
    @Override
    public EncryptedData getEncryptedData() { return encryptedPrivateKey; }

    @Override
    public EncryptionType getEncryptionType() {
        return keyCrypter != null ? keyCrypter.getUnderstoodEncryptionType() : EncryptionType.UNENCRYPTED;
    }

    @Nullable
    public KeyCrypter getKeyCrypter() { return keyCrypter; }

    public EncryptedData getEncryptedPrivateKey() { return encryptedPrivateKey; }

    private byte[] encodePrivateKeys() {
        byte[] mlBytes = mlDsaPrivateKey != null ? mlDsaPrivateKey : new byte[0];
        byte[] slhBytes = slhDsaPrivateKey != null ? slhDsaPrivateKey : new byte[0];
        byte[] result = new byte[4 + mlBytes.length + 4 + slhBytes.length];
        Utils.uint32ToByteArrayBE(mlBytes.length, result, 0);
        System.arraycopy(mlBytes, 0, result, 4, mlBytes.length);
        Utils.uint32ToByteArrayBE(slhBytes.length, result, 4 + mlBytes.length);
        System.arraycopy(slhBytes, 0, result, 8 + mlBytes.length, slhBytes.length);
        return result;
    }

    private static PQKey decodePrivateKeys(byte[] data, KeyBundle bundle) {
        int mlLen = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                  | ((data[2] & 0xFF) << 8)  | (data[3] & 0xFF);
        byte[] mlPriv = Arrays.copyOfRange(data, 4, 4 + mlLen);
        int offset = 4 + mlLen;
        int slhLen = ((data[offset] & 0xFF) << 24) | ((data[offset+1] & 0xFF) << 16)
                   | ((data[offset+2] & 0xFF) << 8)  | (data[offset+3] & 0xFF);
        byte[] slhPriv = Arrays.copyOfRange(data, offset + 4, offset + 4 + slhLen);
        return new PQKey(mlPriv, slhPriv, bundle);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PQKey)) return false;
        PQKey other = (PQKey) o;
        return keyBundle.equals(other.keyBundle);
    }

    @Override
    public int hashCode() {
        return keyBundle.hashCode();
    }

    @Override
    public String toString() {
        return "PQKey{addr=" + (address != null ? address.toHex().substring(0, 16) + "..." : "no-addr")
             + " hasPriv=" + hasPrivateKey()
             + " enc=" + isEncrypted() + "}";
    }

    public static final Comparator<PQKey> PUBKEY_COMPARATOR = new Comparator<PQKey>() {
        @Override
        public int compare(PQKey k1, PQKey k2) {
            byte[] b1 = k1.getPublicKeyBytes();
            byte[] b2 = k2.getPublicKeyBytes();
            int len = Math.min(b1.length, b2.length);
            for (int i = 0; i < len; i++) {
                int cmp = (b1[i] & 0xFF) - (b2[i] & 0xFF);
                if (cmp != 0) return cmp;
            }
            return b1.length - b2.length;
        }
    };

    public static final Comparator<PQKey> AGE_COMPARATOR = new Comparator<PQKey>() {
        @Override
        public int compare(PQKey k1, PQKey k2) {
            long t1 = k1.getCreationTimeSeconds();
            long t2 = k2.getCreationTimeSeconds();
            return Long.compare(t1, t2);
        }
    };

    public byte[] getPubKeyHash() {
        return Utils.sha256hash160(getPubKey());
    }

    public SignatureBundle sign(Sha256Hash input, @Nullable KeyParameter aesKey) throws KeyCrypterException {
        if (isEncrypted()) {
            if (aesKey == null)
                throw new KeyCrypterException("AES key required for encrypted key");
            PQKey decrypted = decrypt(aesKey);
            return decrypted.sign(input);
        }
        return sign(input);
    }

    public ECPoint getPubKeyPoint() {
        return null;
    }

    public BigInteger getPrivKey() {
        throw new UnsupportedOperationException("PQKey does not have a single EC private key");
    }

    public byte[] getPrivKeyBytes() {
        throw new UnsupportedOperationException("PQKey does not have EC private key bytes");
    }

    public boolean isWatching() {
        return !hasPrivateKey();
    }

    public void formatKeyWithAddress(boolean includePrivateKeys, StringBuilder builder, NetworkParameters params) {
        builder.append("  addr:").append(toAddress(params).toHex());
        builder.append("  hash160:").append(Utils.HEX.encode(getPubKeyHash()));
        builder.append("\n");
    }

    public static class MissingPrivateKeyException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public static class KeyIsEncryptedException extends MissingPrivateKeyException {
        private static final long serialVersionUID = 1L;
    }
}
