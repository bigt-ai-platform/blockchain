import { EncryptedData } from './EncryptedData';
import { KeyParameter } from './IESEngine';

export enum EncryptionType {
    UNENCRYPTED = "UNENCRYPTED",
    ENCRYPTED_SCRYPT_AES = "ENCRYPTED_SCRYPT_AES",
    // Add other encryption types as needed from Protos.Wallet.EncryptionType
}

/**
 * Provides a uniform way to access something that can be optionally encrypted with a
 * {@link net.bigtangle.crypto.KeyCrypter}, yielding an {@link net.bigtangle.crypto.EncryptedData}, and
 * which can have a creation time associated with it.
 */
export interface EncryptableItem {
    /** Returns whether the item is encrypted or not. If it is, then {@link #getSecretBytes()} will return null. */
    isEncrypted(): boolean;

    /** Returns the raw bytes of the item, if not encrypted, or null if encrypted or the secret is missing. */
    getSecretBytes(): Uint8Array | null;

    /** Returns the initialization vector and encrypted secret bytes, or null if not encrypted. */
    getEncryptedData(): EncryptedData | null;

    /** Returns an enum constant describing what algorithm was used to encrypt the key or UNENCRYPTED. */
    getEncryptionType(): EncryptionType;

    /** Returns the time in seconds since the UNIX epoch at which this encryptable item was first created/derived. */
    getCreationTimeSeconds(): number;
}