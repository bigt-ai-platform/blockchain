/*******************************************************************************
 *  Copyright   2018  Inasset GmbH.
 *
 *******************************************************************************/
/*
 * Copyright 2011 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.bigtangle.core;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;

import jakarta.annotation.Nullable;

import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.bouncycastle.math.ec.FixedPointUtil;

import com.google.common.base.Objects;
import com.google.common.primitives.Ints;
import com.google.common.primitives.UnsignedBytes;

import net.bigtangle.crypto.EncryptedData;
import net.bigtangle.crypto.EncryptionType;
import net.bigtangle.crypto.KeyCrypter;
import net.bigtangle.crypto.KeyCrypterException;
import net.bigtangle.crypto.LazyECPoint;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.params.NetworkParameters;

/**
 * <p>Represents a legacy elliptic curve (secp256k1/ECDSA) public and (optionally) private key.
 * Re-introduced alongside {@link PQKey} so legacy addresses can be spent (migrating funds to PQ).
 * Signing and verification use Bouncy Castle; the native secp256k1 JNI bridge was removed.</p>
 */
public class ECKey implements Key {
    /** Sorts oldest keys first, newest last. */
    public static final Comparator<ECKey> AGE_COMPARATOR = (k1, k2) -> Long.compare(k1.creationTimeSeconds, k2.creationTimeSeconds);

    /** Compares pub key bytes lexicographically. */
    public static final Comparator<ECKey> PUBKEY_COMPARATOR = (k1, k2) -> UnsignedBytes.lexicographicalComparator().compare(k1.getPubKey(), k2.getPubKey());

    private static final X9ECParameters CURVE_PARAMS = SECNamedCurves.getByName("secp256k1");

    /** The parameters of the secp256k1 curve. */
    public static final ECDomainParameters CURVE;

    /** Equal to CURVE.getN().shiftRight(1), used for canonicalising the S value of a signature. */
    public static final BigInteger HALF_CURVE_ORDER;

    private static final SecureRandom secureRandom;

    static {
        FixedPointUtil.precompute(CURVE_PARAMS.getG());
        CURVE = new ECDomainParameters(CURVE_PARAMS.getCurve(), CURVE_PARAMS.getG(), CURVE_PARAMS.getN(), CURVE_PARAMS.getH());
        HALF_CURVE_ORDER = CURVE_PARAMS.getN().shiftRight(1);
        secureRandom = new SecureRandom();
    }

    // The two parts of the key. If "priv" is set, "pub" can always be calculated.
    protected final BigInteger priv;
    protected final LazyECPoint pub;

    protected long creationTimeSeconds;

    protected KeyCrypter keyCrypter;
    protected EncryptedData encryptedPrivateKey;

    private byte[] pubKeyHash;

    /** Generates an entirely new keypair (compressed public key). */
    public ECKey() {
        this(secureRandom);
    }

    /** Generates an entirely new keypair with the given {@link SecureRandom} (compressed public key). */
    public ECKey(SecureRandom secureRandom) {
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        ECKeyGenerationParameters keygenParams = new ECKeyGenerationParameters(CURVE, secureRandom);
        generator.init(keygenParams);
        AsymmetricCipherKeyPair keypair = generator.generateKeyPair();
        ECPrivateKeyParameters privParams = (ECPrivateKeyParameters) keypair.getPrivate();
        ECPublicKeyParameters pubParams = (ECPublicKeyParameters) keypair.getPublic();
        priv = privParams.getD();
        pub = new LazyECPoint(CURVE.getCurve(), pubParams.getQ().getEncoded(true));
        creationTimeSeconds = Utils.currentTimeSeconds();
    }

    protected ECKey(@Nullable BigInteger priv, ECPoint pub) {
        if (priv != null) {
            checkArgument(!priv.equals(BigInteger.ZERO));
            checkArgument(!priv.equals(BigInteger.ONE));
        }
        this.priv = priv;
        this.pub = new LazyECPoint(checkNotNull(pub));
        this.creationTimeSeconds = Utils.currentTimeSeconds();
    }

    protected ECKey(@Nullable BigInteger priv, LazyECPoint pub) {
        if (priv != null) {
            checkArgument(!priv.equals(BigInteger.ZERO));
            checkArgument(!priv.equals(BigInteger.ONE));
        }
        this.priv = priv;
        this.pub = checkNotNull(pub);
        this.creationTimeSeconds = Utils.currentTimeSeconds();
    }

    @Override
    public KeyType getKeyType() {
        return KeyType.EC;
    }

    public static ECKey createNew() {
        return new ECKey();
    }

    public static ECKey fromPrivateString(String privKey) {
        return ECKey.fromPrivate(Utils.HEX.decode(privKey));
    }

    public static ECKey fromPrivateByte(byte[] privKeyBytes) {
        return ECKey.fromPrivate(privKeyBytes);
    }

    public static ECKey fromPrivate(BigInteger privKey) {
        return fromPrivate(privKey, true);
    }

    public static ECKey fromPrivate(BigInteger privKey, boolean compressed) {
        ECPoint point = publicPointFromPrivate(privKey).normalize();
        return new ECKey(privKey, new LazyECPoint(point, compressed));
    }

    public static ECKey fromPrivate(byte[] privKeyBytes) {
        return fromPrivate(new BigInteger(1, privKeyBytes));
    }

    public static ECKey fromPrivate(byte[] privKeyBytes, boolean compressed) {
        return fromPrivate(new BigInteger(1, privKeyBytes), compressed);
    }

    public static ECKey fromPrivateAndPrecalculatedPublic(BigInteger priv, ECPoint pub) {
        return new ECKey(priv, pub);
    }

    public static ECKey fromPrivateAndPrecalculatedPublic(byte[] priv, byte[] pub) {
        checkNotNull(priv);
        checkNotNull(pub);
        return new ECKey(new BigInteger(1, priv), CURVE.getCurve().decodePoint(pub));
    }

    public static ECKey fromPublicOnly(ECPoint pub) {
        return new ECKey(null, pub);
    }

    public static ECKey fromPublicOnly(byte[] pub) {
        return new ECKey(null, CURVE.getCurve().decodePoint(pub));
    }

    /** Returns a copy of this key, but with the public point in uncompressed form. */
    public ECKey decompress() {
        if (!pub.isCompressed())
            return this;
        return new ECKey(priv, new LazyECPoint(pub.get().normalize(), false));
    }

    public static ECKey fromEncrypted(EncryptedData encryptedPrivateKey, KeyCrypter crypter, byte[] pubKey) {
        ECKey key = fromPublicOnly(pubKey);
        key.encryptedPrivateKey = checkNotNull(encryptedPrivateKey);
        key.keyCrypter = checkNotNull(crypter);
        return key;
    }

    public boolean isPubKeyOnly() {
        return priv == null;
    }

    public boolean hasPrivKey() {
        return priv != null;
    }

    @Override
    public boolean hasPrivateKey() {
        return hasPrivKey();
    }

    @Override
    public boolean isWatching() {
        return isPubKeyOnly() && !isEncrypted();
    }

    public static byte[] publicKeyFromPrivate(BigInteger privKey, boolean compressed) {
        return publicPointFromPrivate(privKey).getEncoded(compressed);
    }

    public static ECPoint publicPointFromPrivate(BigInteger privKey) {
        if (privKey.bitLength() > CURVE.getN().bitLength()) {
            privKey = privKey.mod(CURVE.getN());
        }
        return new FixedPointCombMultiplier().multiply(CURVE.getG(), privKey);
    }

    @Override
    public byte[] getPubKeyHash() {
        if (pubKeyHash == null)
            pubKeyHash = Utils.sha256hash160(this.pub.getEncoded());
        return pubKeyHash;
    }

    @Override
    public byte[] getPubKey() {
        return pub.getEncoded();
    }

    public byte[] getPublicKeyBytes() {
        return getPubKey();
    }

    public ECPoint getPubKeyPoint() {
        return pub.get();
    }

    public BigInteger getPrivKey() {
        if (priv == null)
            throw new MissingPrivateKeyException();
        return priv;
    }

    public boolean isCompressed() {
        return pub.isCompressed();
    }

    /** Returns the legacy address corresponding to the public part of this key (base58 hash160). */
    public Address toAddress(NetworkParameters params) {
        return Address.fromHash160(params, getPubKeyHash());
    }

    @Override
    public String toAddressString(NetworkParameters params) {
        return toAddress(params).toBase58();
    }

    public TransactionSignature sign(Sha256Hash input) throws KeyCrypterException {
        return sign(input, null);
    }

    public TransactionSignature sign(Sha256Hash input, @Nullable KeyParameter aesKey) throws KeyCrypterException {
        KeyCrypter crypter = getKeyCrypter();
        if (crypter != null) {
            if (aesKey == null)
                throw new KeyIsEncryptedException();
            return decrypt(aesKey).sign(input);
        }
        if (priv == null)
            throw new MissingPrivateKeyException();
        return doSign(input, priv);
    }

    protected TransactionSignature doSign(Sha256Hash input, BigInteger privateKeyForSigning) {
        checkNotNull(privateKeyForSigning);
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        ECPrivateKeyParameters privKey = new ECPrivateKeyParameters(privateKeyForSigning, CURVE);
        signer.init(true, privKey);
        BigInteger[] components = signer.generateSignature(input.getBytes());
        TransactionSignature sig = new TransactionSignature(components[0], components[1]);
        return sig.toCanonicalised();
    }

    /** Verifies the given ECDSA signature against the message hash using the public key bytes. */
    public static boolean verify(byte[] data, TransactionSignature signature, byte[] pub) {
        ECDSASigner signer = new ECDSASigner();
        ECPublicKeyParameters params = new ECPublicKeyParameters(CURVE.getCurve().decodePoint(pub), CURVE);
        signer.init(false, params);
        try {
            return signer.verifySignature(data, signature.r, signature.s);
        } catch (NullPointerException e) {
            return false;
        }
    }

    /** Verifies the given ASN.1/DER encoded ECDSA signature against a hash using the public key. */
    public static boolean verify(byte[] data, byte[] signature, byte[] pub) {
        return verify(data, TransactionSignature.decodeFromBitcoin(signature, false, false), pub);
    }

    public boolean verify(byte[] hash, byte[] signature) {
        return ECKey.verify(hash, signature, getPubKey());
    }

    public boolean verify(Sha256Hash sigHash, TransactionSignature signature) {
        return ECKey.verify(sigHash.getBytes(), signature, getPubKey());
    }

    public static boolean isPubKeyCanonical(byte[] pubkey) {
        if (pubkey.length < 33)
            return false;
        if (pubkey[0] == 0x04) {
            if (pubkey.length != 65)
                return false;
        } else if (pubkey[0] == 0x02 || pubkey[0] == 0x03) {
            if (pubkey.length != 33)
                return false;
        } else
            return false;
        return true;
    }

    @Override
    public byte[] getSecretBytes() {
        if (hasPrivKey())
            return getPrivKeyBytes();
        else
            return null;
    }

    public byte[] getPrivKeyBytes() {
        return Utils.bigIntegerToBytes(getPrivKey(), 32);
    }

    @Override
    public long getCreationTimeSeconds() {
        return creationTimeSeconds;
    }

    @Override
    public void setCreationTimeSeconds(long newCreationTimeSeconds) {
        if (newCreationTimeSeconds < 0)
            throw new IllegalArgumentException("Cannot set creation time to negative value: " + newCreationTimeSeconds);
        creationTimeSeconds = newCreationTimeSeconds;
    }

    @Override
    public ECKey encrypt(KeyCrypter keyCrypter, KeyParameter aesKey) throws KeyCrypterException {
        checkNotNull(keyCrypter);
        final byte[] privKeyBytes = getPrivKeyBytes();
        EncryptedData encryptedPrivateKey = keyCrypter.encrypt(privKeyBytes, aesKey);
        ECKey result = ECKey.fromEncrypted(encryptedPrivateKey, keyCrypter, getPubKey());
        result.setCreationTimeSeconds(creationTimeSeconds);
        return result;
    }

    @Override
    public ECKey decrypt(KeyCrypter keyCrypter, KeyParameter aesKey) throws KeyCrypterException {
        checkNotNull(keyCrypter);
        if (this.keyCrypter != null && !this.keyCrypter.equals(keyCrypter))
            throw new KeyCrypterException("The keyCrypter being used to decrypt the key is different to the one that was used to encrypt it");
        checkState(encryptedPrivateKey != null, "This key is not encrypted");
        byte[] unencryptedPrivateKey = keyCrypter.decrypt(encryptedPrivateKey, aesKey);
        ECKey key = ECKey.fromPrivate(unencryptedPrivateKey);
        if (!isCompressed())
            key = key.decompress();
        if (!Arrays.equals(key.getPubKey(), getPubKey()))
            throw new KeyCrypterException("Provided AES key is wrong");
        key.setCreationTimeSeconds(creationTimeSeconds);
        return key;
    }

    @Override
    public ECKey decrypt(KeyParameter aesKey) throws KeyCrypterException {
        final KeyCrypter crypter = getKeyCrypter();
        if (crypter == null)
            throw new KeyCrypterException("No key crypter available");
        return decrypt(crypter, aesKey);
    }

    public ECKey maybeDecrypt(@Nullable KeyParameter aesKey) throws KeyCrypterException {
        return isEncrypted() && aesKey != null ? decrypt(aesKey) : this;
    }

    @Override
    public boolean isEncrypted() {
        return keyCrypter != null && encryptedPrivateKey != null && encryptedPrivateKey.encryptedBytes.length > 0;
    }

    @Override
    public EncryptionType getEncryptionType() {
        return keyCrypter != null ? keyCrypter.getUnderstoodEncryptionType() : EncryptionType.UNENCRYPTED;
    }

    @Override
    @Nullable
    public EncryptedData getEncryptedData() {
        return getEncryptedPrivateKey();
    }

    @Override
    @Nullable
    public EncryptedData getEncryptedPrivateKey() {
        return encryptedPrivateKey;
    }

    @Override
    @Nullable
    public KeyCrypter getKeyCrypter() {
        return keyCrypter;
    }

    @Override
    public String getPublicKeyAsHex() {
        return Utils.HEX.encode(getPubKey());
    }

    public String getPrivateKeyAsHex() {
        return Utils.HEX.encode(getPrivKeyBytes());
    }

    /**
     * Exports the private key in WIF (Wallet Import Format, base58) for legacy
     * wallet-file compatibility.
     */
    public net.bigtangle.utils.DumpedPrivateKey getPrivateKeyEncoded(NetworkParameters params) {
        return new net.bigtangle.utils.DumpedPrivateKey(params, getPrivKeyBytes(), isCompressed());
    }

    public String getPrivateKeyAsWiF(NetworkParameters params) {
        return getPrivateKeyEncoded(params).toString();
    }

    /** Imports a private key from its WIF (base58) representation. */
    public static ECKey fromWIF(NetworkParameters params, String wif) throws net.bigtangle.exception.AddressFormatException {
        return net.bigtangle.utils.DumpedPrivateKey.fromBase58(params, wif).getKey();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof ECKey)) return false;
        ECKey other = (ECKey) o;
        return Objects.equal(this.priv, other.priv)
                && Objects.equal(this.pub, other.pub)
                && Objects.equal(this.creationTimeSeconds, other.creationTimeSeconds)
                && Objects.equal(this.keyCrypter, other.keyCrypter)
                && Objects.equal(this.encryptedPrivateKey, other.encryptedPrivateKey);
    }

    @Override
    public int hashCode() {
        byte[] bits = getPubKey();
        return Ints.fromBytes(bits[0], bits[1], bits[2], bits[3]);
    }

    @Override
    public String toString() {
        return "ECKey{pub=" + getPublicKeyAsHex().substring(0, 16) + "... hasPriv=" + hasPrivKey() + "}";
    }

    @Override
    public void formatKeyWithAddress(boolean includePrivateKeys, StringBuilder builder, NetworkParameters params) {
        final Address address = toAddress(params);
        builder.append("  addr:");
        builder.append(address.toString());
        builder.append("  hash160:");
        builder.append(Utils.HEX.encode(getPubKeyHash()));
        if (creationTimeSeconds > 0)
            builder.append("  creationTimeSeconds:").append(creationTimeSeconds);
        builder.append("\n");
        if (includePrivateKeys) {
            builder.append("  priv HEX:").append(getPrivateKeyAsHex());
            builder.append("\n");
        }
    }

    public static class MissingPrivateKeyException extends Key.MissingPrivateKeyException {
        private static final long serialVersionUID = 1L;
    }

    public static class KeyIsEncryptedException extends Key.KeyIsEncryptedException {
        private static final long serialVersionUID = 1L;
    }
}
