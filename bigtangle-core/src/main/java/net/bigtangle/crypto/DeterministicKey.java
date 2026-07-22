/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2013 Matija Mazi.
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
package net.bigtangle.crypto;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import net.bigtangle.core.*;
import net.bigtangle.crypto.pq.KeyBundle;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.utils.Base58;

import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.ECPoint;

import jakarta.annotation.Nullable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

import static com.google.common.base.Preconditions.*;
import static net.bigtangle.core.Utils.HEX;

public class DeterministicKey extends PQKey {

    public static final ECDomainParameters CURVE;

    static {
        X9ECParameters params = SECNamedCurves.getByName("secp256k1");
        CURVE = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
    }

    public static final BigInteger HALF_CURVE_ORDER = CURVE.getN().shiftRight(1);

    public static final Comparator<PQKey> CHILDNUM_ORDER = new Comparator<PQKey>() {
        @Override
        public int compare(PQKey k1, PQKey k2) {
            ChildNumber cn1 = ((DeterministicKey) k1).getChildNumber();
            ChildNumber cn2 = ((DeterministicKey) k2).getChildNumber();
            return cn1.compareTo(cn2);
        }
    };

    private final DeterministicKey parent;
    private final ImmutableList<ChildNumber> childNumberPath;
    private final int depth;
    private int parentFingerprint;

    private final byte[] chainCode;

    protected BigInteger priv;
    protected LazyECPoint pub;
    protected long creationTimeSeconds;
    protected KeyCrypter keyCrypter;
    protected EncryptedData encryptedPrivateKey;

    public DeterministicKey(ImmutableList<ChildNumber> childNumberPath,
                            byte[] chainCode,
                            LazyECPoint publicAsPoint,
                            @Nullable BigInteger priv,
                            @Nullable DeterministicKey parent) {
        checkArgument(chainCode.length == 32);
        this.priv = priv;
        this.pub = new LazyECPoint(checkNotNull(publicAsPoint).get(), true);
        this.parent = parent;
        this.childNumberPath = checkNotNull(childNumberPath);
        this.chainCode = Arrays.copyOf(chainCode, chainCode.length);
        this.depth = parent == null ? 0 : parent.depth + 1;
        this.parentFingerprint = (parent != null) ? parent.getFingerprint() : 0;
        this.creationTimeSeconds = Utils.currentTimeSeconds();
    }

    public DeterministicKey(ImmutableList<ChildNumber> childNumberPath,
                            byte[] chainCode,
                            ECPoint publicAsPoint,
                            @Nullable BigInteger priv,
                            @Nullable DeterministicKey parent) {
        this(childNumberPath, chainCode, new LazyECPoint(publicAsPoint), priv, parent);
    }

    public DeterministicKey(ImmutableList<ChildNumber> childNumberPath,
                            byte[] chainCode,
                            BigInteger priv,
                            @Nullable DeterministicKey parent) {
        checkArgument(chainCode.length == 32);
        this.priv = priv;
        this.pub = new LazyECPoint(CURVE.getG().multiply(priv), true);
        this.parent = parent;
        this.childNumberPath = checkNotNull(childNumberPath);
        this.chainCode = Arrays.copyOf(chainCode, chainCode.length);
        this.depth = parent == null ? 0 : parent.depth + 1;
        this.parentFingerprint = (parent != null) ? parent.getFingerprint() : 0;
        this.creationTimeSeconds = Utils.currentTimeSeconds();
    }

    public DeterministicKey(ImmutableList<ChildNumber> childNumberPath,
                            byte[] chainCode,
                            KeyCrypter crypter,
                            LazyECPoint pub,
                            EncryptedData priv,
                            @Nullable DeterministicKey parent) {
        this(childNumberPath, chainCode, pub, null, parent);
        this.encryptedPrivateKey = checkNotNull(priv);
        this.keyCrypter = checkNotNull(crypter);
    }

    private int ascertainParentFingerprint(DeterministicKey parentKey, int parentFingerprint)
    throws IllegalArgumentException {
        if (parentFingerprint != 0) {
            if (parent != null)
                checkArgument(parent.getFingerprint() == parentFingerprint,
                              "parent fingerprint mismatch",
                              Integer.toHexString(parent.getFingerprint()), Integer.toHexString(parentFingerprint));
            return parentFingerprint;
        } else return 0;
    }

    private DeterministicKey(ImmutableList<ChildNumber> childNumberPath,
                            byte[] chainCode,
                            LazyECPoint publicAsPoint,
                            @Nullable DeterministicKey parent,
                            int depth,
                            int parentFingerprint) {
        checkArgument(chainCode.length == 32);
        this.priv = null;
        this.pub = new LazyECPoint(checkNotNull(publicAsPoint).get(), true);
        this.parent = parent;
        this.childNumberPath = checkNotNull(childNumberPath);
        this.chainCode = Arrays.copyOf(chainCode, chainCode.length);
        this.depth = depth;
        this.parentFingerprint = ascertainParentFingerprint(parent, parentFingerprint);
        this.creationTimeSeconds = Utils.currentTimeSeconds();
    }

    private DeterministicKey(ImmutableList<ChildNumber> childNumberPath,
                            byte[] chainCode,
                            BigInteger priv,
                            @Nullable DeterministicKey parent,
                            int depth,
                            int parentFingerprint) {
        checkArgument(chainCode.length == 32);
        this.priv = priv;
        this.pub = new LazyECPoint(CURVE.getG().multiply(priv), true);
        this.parent = parent;
        this.childNumberPath = checkNotNull(childNumberPath);
        this.chainCode = Arrays.copyOf(chainCode, chainCode.length);
        this.depth = depth;
        this.parentFingerprint = ascertainParentFingerprint(parent, parentFingerprint);
        this.creationTimeSeconds = Utils.currentTimeSeconds();
    }

    public DeterministicKey(DeterministicKey keyToClone, DeterministicKey newParent) {
        this.priv = keyToClone.priv;
        this.pub = keyToClone.pub;
        this.parent = newParent;
        this.childNumberPath = keyToClone.childNumberPath;
        this.chainCode = keyToClone.chainCode;
        this.encryptedPrivateKey = keyToClone.encryptedPrivateKey;
        this.keyCrypter = keyToClone.keyCrypter;
        this.depth = this.childNumberPath.size();
        this.parentFingerprint = this.parent.getFingerprint();
        this.creationTimeSeconds = keyToClone.creationTimeSeconds;
    }

    public ImmutableList<ChildNumber> getPath() {
        return childNumberPath;
    }

    public String getPathAsString() {
        return HDUtils.formatPath(getPath());
    }

    public int getDepth() {
        return depth;
    }

    public ChildNumber getChildNumber() {
        return childNumberPath.size() == 0 ? ChildNumber.ZERO : childNumberPath.get(childNumberPath.size() - 1);
    }

    public byte[] getChainCode() {
        return chainCode;
    }

    public byte[] getIdentifier() {
        return Utils.sha256hash160(getPubKey());
    }

    public int getFingerprint() {
        return ByteBuffer.wrap(Arrays.copyOfRange(getIdentifier(), 0, 4)).getInt();
    }

    @Nullable
    public DeterministicKey getParent() {
        return parent;
    }

    public int getParentFingerprint() {
        return parentFingerprint;
    }

    public byte[] getPrivKeyBytes33() {
        byte[] bytes33 = new byte[33];
        byte[] priv = getPrivKeyBytes();
        System.arraycopy(priv, 0, bytes33, 33 - priv.length, priv.length);
        return bytes33;
    }

    public DeterministicKey dropPrivateBytes() {
        if (isPubKeyOnly())
            return this;
        else
            return new DeterministicKey(getPath(), getChainCode(), pub, null, parent);
    }

    public DeterministicKey dropParent() {
        DeterministicKey key = new DeterministicKey(getPath(), getChainCode(), pub, priv, null);
        key.parentFingerprint = parentFingerprint;
        return key;
    }

    static byte[] addChecksum(byte[] input) {
        int inputLength = input.length;
        byte[] checksummed = new byte[inputLength + 4];
        System.arraycopy(input, 0, checksummed, 0, inputLength);
        byte[] checksum = Sha256Hash.hashTwice(input);
        System.arraycopy(checksum, 0, checksummed, inputLength, 4);
        return checksummed;
    }

    public DeterministicKey encrypt(KeyCrypter keyCrypter, KeyParameter aesKey) throws KeyCrypterException {
        throw new UnsupportedOperationException("Must supply a new parent for encryption");
    }

    public DeterministicKey encrypt(KeyCrypter keyCrypter, KeyParameter aesKey, @Nullable DeterministicKey newParent) throws KeyCrypterException {
        checkNotNull(keyCrypter);
        if (newParent != null)
            checkArgument(newParent.isEncrypted());
        final byte[] privKeyBytes = getPrivKeyBytes();
        checkState(privKeyBytes != null, "Private key is not available");
        EncryptedData encryptedPrivateKey = keyCrypter.encrypt(privKeyBytes, aesKey);
        DeterministicKey key = new DeterministicKey(childNumberPath, chainCode, keyCrypter, pub, encryptedPrivateKey, newParent);
        if (newParent == null)
            key.setCreationTimeSeconds(getCreationTimeSeconds());
        return key;
    }

    public boolean isPubKeyOnly() {
        return priv == null && (parent == null || parent.isPubKeyOnly());
    }

    public boolean hasPrivKey() {
        return findParentWithPrivKey() != null;
    }

    @Nullable
    public byte[] getSecretBytes() {
        return priv != null ? getPrivKeyBytes() : null;
    }

    public boolean isEncrypted() {
        return priv == null && (encryptedPrivateKey != null || (parent != null && parent.isEncrypted()));
    }

    @Nullable
    public KeyCrypter getKeyCrypter() {
        if (keyCrypter != null)
            return keyCrypter;
        else if (parent != null)
            return parent.getKeyCrypter();
        else
            return null;
    }

    public final TransactionSignature ecSign(Sha256Hash input, @Nullable KeyParameter aesKey) throws KeyCrypterException {
        if (isEncrypted()) {
            if (aesKey == null)
                throw new KeyCrypterException("AES key required for encrypted key");
            DeterministicKey decrypted = decrypt(aesKey);
            return decrypted.ecSign(input, null);
        } else {
            final BigInteger privateKey = findOrDerivePrivateKey();
            if (privateKey == null) {
                throw new MissingPrivateKeyException();
            }
            return doSign(input, privateKey);
        }
    }

    public DeterministicKey decrypt(KeyCrypter keyCrypter, KeyParameter aesKey) throws KeyCrypterException {
        checkNotNull(keyCrypter);
        if (this.keyCrypter != null && !this.keyCrypter.equals(keyCrypter))
            throw new KeyCrypterException("The keyCrypter being used to decrypt the key is different to the one that was used to encrypt it");
        BigInteger privKey = findOrDeriveEncryptedPrivateKey(keyCrypter, aesKey);
        DeterministicKey key = new DeterministicKey(childNumberPath, chainCode, privKey, parent);
        if (!Arrays.equals(key.getPubKey(), getPubKey()))
            throw new KeyCrypterException("Provided AES key is wrong");
        if (parent == null)
            key.setCreationTimeSeconds(getCreationTimeSeconds());
        return key;
    }

    public DeterministicKey decrypt(KeyParameter aesKey) throws KeyCrypterException {
        if (keyCrypter == null)
            throw new KeyCrypterException("No key crypter available");
        return decrypt(keyCrypter, aesKey);
    }

    private BigInteger findOrDeriveEncryptedPrivateKey(KeyCrypter keyCrypter, KeyParameter aesKey) {
        if (encryptedPrivateKey != null)
            return new BigInteger(1, keyCrypter.decrypt(encryptedPrivateKey, aesKey));
        DeterministicKey cursor = parent;
        while (cursor != null) {
            if (cursor.encryptedPrivateKey != null) break;
            cursor = cursor.parent;
        }
        if (cursor == null)
            throw new KeyCrypterException("Neither this key nor its parents have an encrypted private key");
        byte[] parentalPrivateKeyBytes = keyCrypter.decrypt(cursor.encryptedPrivateKey, aesKey);
        return derivePrivateKeyDownwards(cursor, parentalPrivateKeyBytes);
    }

    private DeterministicKey findParentWithPrivKey() {
        DeterministicKey cursor = this;
        while (cursor != null) {
            if (cursor.priv != null) break;
            cursor = cursor.parent;
        }
        return cursor;
    }

    @Nullable
    private BigInteger findOrDerivePrivateKey() {
        DeterministicKey cursor = findParentWithPrivKey();
        if (cursor == null)
            return null;
        return derivePrivateKeyDownwards(cursor, cursor.priv.toByteArray());
    }

    private BigInteger derivePrivateKeyDownwards(DeterministicKey cursor, byte[] parentalPrivateKeyBytes) {
        DeterministicKey downCursor = new DeterministicKey(cursor.childNumberPath, cursor.chainCode,
                cursor.pub, new BigInteger(1, parentalPrivateKeyBytes), cursor.parent);
        ImmutableList<ChildNumber> path = childNumberPath.subList(cursor.getPath().size(), childNumberPath.size());
        for (ChildNumber num : path) {
            downCursor = HDKeyDerivation.deriveChildKey(downCursor, num);
        }
        if (!downCursor.pub.equals(pub))
            throw new KeyCrypterException("Could not decrypt bytes");
        return checkNotNull(downCursor.priv);
    }

    public DeterministicKey derive(int child) {
        return HDKeyDerivation.deriveChildKey(this, new ChildNumber(child, true));
    }

    public BigInteger getPrivKey() {
        final BigInteger key = findOrDerivePrivateKey();
        checkState(key != null, "Private key bytes not available");
        return key;
    }

    public byte[] serializePublic(NetworkParameters params) {
        return serialize(params, true);
    }

    public byte[] serializePrivate(NetworkParameters params) {
        return serialize(params, false);
    }

    private byte[] serialize(NetworkParameters params, boolean pub) {
        ByteBuffer ser = ByteBuffer.allocate(78);
        ser.putInt(pub ? params.getBip32HeaderPub() : params.getBip32HeaderPriv());
        ser.put((byte) getDepth());
        ser.putInt(getParentFingerprint());
        ser.putInt(getChildNumber().i());
        ser.put(getChainCode());
        ser.put(pub ? getPubKey() : getPrivKeyBytes33());
        checkState(ser.position() == 78);
        return ser.array();
    }

    public String serializePubB58(NetworkParameters params) {
        return toBase58(serialize(params, true));
    }

    public String serializePrivB58(NetworkParameters params) {
        return toBase58(serialize(params, false));
    }

    static String toBase58(byte[] ser) {
        return Base58.encode(addChecksum(ser));
    }

    public static DeterministicKey deserializeB58(String base58, NetworkParameters params) {
        return deserializeB58(null, base58, params);
    }

    public static DeterministicKey deserializeB58(@Nullable DeterministicKey parent, String base58, NetworkParameters params) {
        return deserialize(params, Base58.decodeChecked(base58), parent);
    }

    public static DeterministicKey deserialize(NetworkParameters params, byte[] serializedKey) {
        return deserialize(params, serializedKey, null);
    }

    public static DeterministicKey deserialize(NetworkParameters params, byte[] serializedKey, @Nullable DeterministicKey parent) {
        ByteBuffer buffer = ByteBuffer.wrap(serializedKey);
        int header = buffer.getInt();
        if (header != params.getBip32HeaderPriv() && header != params.getBip32HeaderPub())
            throw new IllegalArgumentException("Unknown header bytes: " + toBase58(serializedKey).substring(0, 4));
        boolean pub = header == params.getBip32HeaderPub();
        int depth = buffer.get() & 0xFF;
        final int parentFingerprint = buffer.getInt();
        final int i = buffer.getInt();
        final ChildNumber childNumber = new ChildNumber(i);
        ImmutableList<ChildNumber> path;
        if (parent != null) {
            if (parentFingerprint == 0)
                throw new IllegalArgumentException("Parent was provided but this key doesn't have one");
            if (parent.getFingerprint() != parentFingerprint)
                throw new IllegalArgumentException("Parent fingerprints don't match");
            path = HDUtils.append(parent.getPath(), childNumber);
            if (path.size() != depth)
                throw new IllegalArgumentException("Depth does not match");
        } else {
            if (depth >= 1)
                path = ImmutableList.of(childNumber);
            else path = ImmutableList.of();
        }
        byte[] chainCode = new byte[32];
        buffer.get(chainCode);
        byte[] data = new byte[33];
        buffer.get(data);
        checkArgument(!buffer.hasRemaining(), "Found unexpected data in key");
        if (pub) {
            return new DeterministicKey(path, chainCode, new LazyECPoint(CURVE.getCurve(), data), parent, depth, parentFingerprint);
        } else {
            return new DeterministicKey(path, chainCode, new BigInteger(1, data), parent, depth, parentFingerprint);
        }
    }

    public long getCreationTimeSeconds() {
        if (parent != null)
            return parent.getCreationTimeSeconds();
        else
            return creationTimeSeconds;
    }

    public void setCreationTimeSeconds(long newCreationTimeSeconds) {
        if (parent != null)
            throw new IllegalStateException("Creation time can only be set on root keys.");
        else
            creationTimeSeconds = newCreationTimeSeconds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeterministicKey other = (DeterministicKey) o;
        boolean pubEq = pub.equals(other.pub);
        return pubEq
                && Arrays.equals(this.chainCode, other.chainCode)
                && Objects.equal(this.childNumberPath, other.childNumberPath);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(pub.hashCode(), Arrays.hashCode(chainCode), childNumberPath);
    }

    @Override
    public String toString() {
        final MoreObjects.ToStringHelper helper = MoreObjects.toStringHelper(this).omitNullValues();
        helper.add("pub", Utils.HEX.encode(pub.getEncoded()));
        helper.add("chainCode", HEX.encode(chainCode));
        helper.add("path", getPathAsString());
        if (creationTimeSeconds > 0)
            helper.add("creationTimeSeconds", creationTimeSeconds);
        helper.add("isEncrypted", isEncrypted());
        helper.add("isPubKeyOnly", isPubKeyOnly());
        return helper.toString();
    }

    public void formatKeyWithAddress(boolean includePrivateKeys, StringBuilder builder, NetworkParameters params) {
        builder.append("  addr:").append(super.toAddress(params).toHex());
        builder.append("  hash160:").append(Utils.HEX.encode(getPubKeyHash()));
        builder.append("  (").append(getPathAsString()).append(")\n");
        if (includePrivateKeys) {
            builder.append("  ").append(toStringWithPrivate(params)).append("\n");
        }
    }

    public byte[] getPubKey() {
        return pub.getEncoded();
    }

    public byte[] getPrivKeyBytes() {
        BigInteger key = getPrivKey();
        byte[] bytes = key.toByteArray();
        if (bytes.length == 33) {
            byte[] tmp = new byte[32];
            System.arraycopy(bytes, 1, tmp, 0, 32);
            return tmp;
        } else if (bytes.length == 32) {
            return bytes;
        } else {
            byte[] tmp = new byte[32];
            System.arraycopy(bytes, 0, tmp, tmp.length - bytes.length, bytes.length);
            return tmp;
        }
    }

    public byte[] getPubKeyHash() {
        return Utils.sha256hash160(getPubKey());
    }

    public String toStringWithPrivate(NetworkParameters params) {
        return toString() + " private: " + Utils.HEX.encode(getPrivKeyBytes());
    }

    public ECPoint getPubKeyPoint() {
        return pub.get();
    }

    public boolean isWatching() {
        return isPubKeyOnly();
    }

    public boolean hasPrivateKey() {
        return hasPrivKey();
    }

    public byte[] getPublicKeyBytes() {
        return getPubKey();
    }

    private static TransactionSignature doSign(Sha256Hash input, BigInteger privateKey) {
        org.bouncycastle.crypto.signers.ECDSASigner signer = new org.bouncycastle.crypto.signers.ECDSASigner();
        org.bouncycastle.crypto.params.ECPrivateKeyParameters privKeyParams = new org.bouncycastle.crypto.params.ECPrivateKeyParameters(privateKey, CURVE);
        signer.init(true, privKeyParams);
        BigInteger[] components = signer.generateSignature(input.getBytes());
        BigInteger r = components[0];
        BigInteger s = components[1];
        if (s.compareTo(HALF_CURVE_ORDER) > 0)
            s = CURVE.getN().subtract(s);
        return new TransactionSignature(r, s);
    }

    public static class MissingPrivateKeyException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public static class KeyIsEncryptedException extends MissingPrivateKeyException {
        private static final long serialVersionUID = 1L;
    }
}
