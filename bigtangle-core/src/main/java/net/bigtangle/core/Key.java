package net.bigtangle.core;

import jakarta.annotation.Nullable;

import org.bouncycastle.crypto.params.KeyParameter;

import net.bigtangle.crypto.EncryptableItem;
import net.bigtangle.crypto.EncryptedData;
import net.bigtangle.crypto.KeyCrypter;
import net.bigtangle.crypto.KeyCrypterException;
import net.bigtangle.params.NetworkParameters;

/**
 * Common contract for both legacy {@code ECKey} (ECDSA/secp256k1) and
 * post-quantum {@code PQKey} (ML-DSA-87 + SLH-DSA). Dispatch on
 * {@link #getKeyType()} for type-specific behaviour (signing, address form).
 */
public interface Key extends EncryptableItem {

    KeyType getKeyType();

    /** Raw public key bytes as placed on the script stack (EC: SEC1 0x02/0x03/0x04; PQ: 0x05-prefixed KeyBundle). */
    byte[] getPubKey();

    byte[] getPublicKeyBytes();

    /** 20-byte hash160 of {@link #getPubKey()}. */
    byte[] getPubKeyHash();

    String getPublicKeyAsHex();

    boolean hasPrivateKey();

    boolean isWatching();

    void setCreationTimeSeconds(long newCreationTimeSeconds);

    @Nullable
    KeyCrypter getKeyCrypter();

    @Nullable
    EncryptedData getEncryptedPrivateKey();

    Key encrypt(KeyCrypter keyCrypter, KeyParameter aesKey) throws KeyCrypterException;

    Key decrypt(KeyCrypter keyCrypter, KeyParameter aesKey) throws KeyCrypterException;

    Key decrypt(KeyParameter aesKey) throws KeyCrypterException;

    void formatKeyWithAddress(boolean includePrivateKeys, StringBuilder builder, NetworkParameters params);

    /** Canonical string form of the key's address (EC: legacy base58 hash160; PQ: hex PQAddress). */
    String toAddressString(NetworkParameters params);

    class MissingPrivateKeyException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    class KeyIsEncryptedException extends MissingPrivateKeyException {
        private static final long serialVersionUID = 1L;
    }
}
