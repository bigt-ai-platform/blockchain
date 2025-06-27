import { EncryptedData } from './EncryptedData';
import { KeyParameter } from './IESEngine'; // Re-using KeyParameter from IESEngine.ts
import { EncryptionType } from './EncryptableItem'; // Re-using EncryptionType from EncryptableItem.ts

export class KeyCrypterException extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "KeyCrypterException";
        Object.setPrototypeOf(this, KeyCrypterException.prototype);
    }
}

/**
 * <p>A KeyCrypter can be used to encrypt and decrypt a message. The sequence of events to encrypt and then decrypt
 * a message are as follows:</p>
 *
 * <p>(1) Ask the user for a password. deriveKey() is then called to create an KeyParameter. This contains the AES
 * key that will be used for encryption.</p>
 * <p>(2) Encrypt the message using encrypt(), providing the message bytes and the KeyParameter from (1). This returns
 * an EncryptedData which contains the encryptedPrivateKey bytes and an initialisation vector.</p>
 * <p>(3) To decrypt an EncryptedData, repeat step (1) to get a KeyParameter, then call decrypt().</p>
 *
 * <p>There can be different algorithms used for encryption/ decryption so the getUnderstoodEncryptionType is used
 * to determine whether any given KeyCrypter can understand the type of encrypted data you have.</p>
 */
export interface KeyCrypter {

    /**
     * Return the EncryptionType enum value which denotes the type of encryption/ decryption that this KeyCrypter
     * can understand.
     */
    getUnderstoodEncryptionType(): EncryptionType;

    /**
     * Create a KeyParameter (which typically contains an AES key)
     * @param password
     * @return KeyParameter The KeyParameter which typically contains the AES key to use for encrypting and decrypting
     * @throws KeyCrypterException
     */
    deriveKey(password: string): KeyParameter;

    /**
     * Decrypt the provided encrypted bytes, converting them into unencrypted bytes.
     *
     * @throws KeyCrypterException if decryption was unsuccessful.
     */
    decrypt(encryptedBytesToDecode: EncryptedData, aesKey: KeyParameter): Uint8Array;

    /**
     * Encrypt the supplied bytes, converting them into ciphertext.
     *
     * @return encryptedPrivateKey An encryptedPrivateKey containing the encrypted bytes and an initialisation vector.
     * @throws KeyCrypterException if encryption was unsuccessful
     */
    encrypt(plainBytes: Uint8Array, aesKey: KeyParameter): EncryptedData;

    equals(other: KeyCrypter): boolean;
}

export { KeyParameter };
