package net.bigtangle.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.annotation.Nullable;

import org.bouncycastle.crypto.params.KeyParameter;
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

    public static PQKey createNew() {
        byte[] seed = new byte[64];
        secureRandom.nextBytes(seed);
        return fromSeeds(Arrays.copyOfRange(seed, 0, 32), Arrays.copyOfRange(seed, 32, 64));
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
        return mlDsaPrivateKey != null && slhDsaPrivateKey != null;
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
        if (mlDsaPrivateKey == null || slhDsaPrivateKey == null)
            throw new MissingPrivateKeyException();
        byte[] txHash = PQScriptUtils.domainSeparatedHash(input.getBytes(), PQConstants.TX_DOMAIN);
        byte[] mlMsg = PQScriptUtils.domainSeparatedHash(txHash, PQConstants.MLDSA_SIG_DOMAIN);
        byte[] slhMsg = PQScriptUtils.domainSeparatedHash(txHash, PQConstants.SLHDSA_SIG_DOMAIN);
        byte[] mlSig = prov.sign(PQConstants.ALG_ML_DSA_87, mlDsaPrivateKey, mlMsg);
        byte[] slhSig = prov.sign(PQConstants.ALG_SLH_DSA_SHA2_256S, slhDsaPrivateKey, slhMsg);
        List<SignatureBundle.Entry> entries = new ArrayList<>();
        entries.add(new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, mlSig));
        entries.add(new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, slhSig));
        return new SignatureBundle(entries);
    }

    public PQAddress toAddress(int network) {
        if (address == null) {
            address = PQAddress.fromKeyBundle(network, PQConstants.SUITE_CAT5_DUAL_1, keyBundle);
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

    public static class MissingPrivateKeyException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public static class KeyIsEncryptedException extends MissingPrivateKeyException {
        private static final long serialVersionUID = 1L;
    }
}
