import { EncryptedData } from './EncryptedData';
import { KeyParameter } from './IESEngine';
import { KeyCrypter, KeyCrypterException } from './KeyCrypter';
import { EncryptionType } from './EncryptableItem';
import * as aes from '@noble/ciphers/aes';
import { randomBytes } from '@noble/hashes/utils';

// Mock Protos.ScryptParameters for now
export class ScryptParameters {
    private readonly _salt: Uint8Array;
    private readonly _n: number;
    private readonly _r: number;
    private readonly _p: number;

    constructor(salt: Uint8Array, n: number = 16384, r: number = 8, p: number = 1) {
        this._salt = salt;
        this._n = n;
        this._r = r;
        this._p = p;
    }

    getSalt(): Uint8Array {
        return this._salt;
    }

    getN(): number {
        return this._n;
    }

    getR(): number {
        return this._r;
    }

    getP(): number {
        return this._p;
    }

    static bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
        if (a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) {
            if (a[i] !== b[i]) return false;
        }
        return true;
    }

    equals(other: ScryptParameters): boolean {
        return ScryptParameters.bytesEqual(this._salt, other._salt) &&
               this._n === other._n &&
               this._r === other._r &&
               this._p === other._p;
    }
}

/**
 * <p>This class encrypts and decrypts byte arrays and strings using scrypt as the
 * key derivation function and AES for the encryption.</p>
 *
 * <p>You can use this class to:</p>
 *
 * <p>1) Using a user password, create an AES key that can encrypt and decrypt your private keys.
 * To convert the password to the AES key, scrypt is used. This is an algorithm resistant
 * to brute force attacks. You can use the ScryptParameters to tune how difficult you
 * want this to be generation to be.</p>
 *
 * <p>2) Using the AES Key generated above, you then can encrypt and decrypt any bytes using
 * the AES symmetric cipher. Eight bytes of salt is used to prevent dictionary attacks.</p>
 */
export class KeyCrypterScrypt implements KeyCrypter {

    /**
     * Key length in bytes.
     */
    public static readonly KEY_LENGTH = 32; // = 256 bits.

    /**
     * The size of an AES block in bytes.
     * This is also the length of the initialisation vector.
     */
    public static readonly BLOCK_LENGTH = 16;  // = 128 bits.

    /**
     * The length of the salt used.
     */
    public static readonly SALT_LENGTH = 8;

    private readonly scryptParameters: ScryptParameters;

    /**
     * Encryption/Decryption using default parameters and a random salt.
     */
    constructor();
    /**
     * Encryption/Decryption using custom number of iterations parameters and a random salt.
     * As of August 2016, a useful value for mobile devices is 4096 (derivation takes about 1 second).
     *
     * @param iterations
     *            number of scrypt iterations
     */
    constructor(iterations: number);
    /**
     * Encryption/ Decryption using specified Scrypt parameters.
     *
     * @param scryptParameters ScryptParameters to use
     * @throws NullPointerException if the scryptParameters or any of its N, R or P is null.
     */
    constructor(scryptParameters: ScryptParameters);
    constructor(param?: number | ScryptParameters) {
        if (typeof param === 'number') {
            this.scryptParameters = new ScryptParameters(KeyCrypterScrypt.randomSalt(), param);
        } else if (param instanceof ScryptParameters) {
            this.scryptParameters = param;
        } else {
            this.scryptParameters = new ScryptParameters(KeyCrypterScrypt.randomSalt());
        }

        // Check there is a non-empty salt.
        if (this.scryptParameters.getSalt() == null || this.scryptParameters.getSalt().length === 0) {
            console.warn("You are using a ScryptParameters with no salt. Your encryption may be vulnerable to a dictionary attack.");
        }
    }

    /** Returns SALT_LENGTH (8) bytes of random data */
    public static randomSalt(): Uint8Array {
        return randomBytes(KeyCrypterScrypt.SALT_LENGTH);
    }

    /**
     * Generate AES key.
     *
     * This is a very slow operation compared to encrypt/ decrypt so it is normally worth caching the result.
     *
     * @param password    The password to use in key generation
     * @return            The KeyParameter containing the created AES key
     * @throws            KeyCrypterException
     */
    public deriveKey(password: string): KeyParameter {
        // scryptSync is not available in @noble/hashes/scrypt. Use scryptAsync and update API to async, or use a different scrypt implementation supporting sync mode.
        throw new KeyCrypterException("scryptSync is not available in @noble/hashes/scrypt. Use scryptAsync and update API to async, or use a different scrypt implementation supporting sync mode.");
    }

    /**
     * Password based encryption using AES - CBC 256 bits.
     */
    public encrypt(plainBytes: Uint8Array, aesKey: KeyParameter): EncryptedData {
        try {
            // Generate iv - each encryption call has a different iv.
            const iv = randomBytes(KeyCrypterScrypt.BLOCK_LENGTH);

            // Encrypt using AES.
            const cipher = aes.cbc(aesKey.key, iv);
            const encryptedBytes = cipher.encrypt(plainBytes);

            return new EncryptedData(iv, encryptedBytes);
        } catch (e: any) {
            throw new KeyCrypterException("Could not encrypt bytes." + e.message);
        }
    }

    /**
     * Decrypt bytes previously encrypted with this class.
     *
     * @param dataToDecrypt    The data to decrypt
     * @param aesKey           The AES key to use for decryption
     * @return                 The decrypted bytes
     * @throws                 KeyCrypterException if bytes could not be decrypted
     */
    public decrypt(dataToDecrypt: EncryptedData, aesKey: KeyParameter): Uint8Array {
        try {
            // Decrypt the message.
            const cipher = aes.cbc(aesKey.key, dataToDecrypt.initialisationVector);
            const decryptedBytes = cipher.decrypt(dataToDecrypt.encryptedBytes);

            return decryptedBytes;
        } catch (e: any) {
            throw new KeyCrypterException("Could not decrypt bytes" + e.message);
        }
    }

    public getScryptParameters(): ScryptParameters {
        return this.scryptParameters;
    }

    /**
     * Return the EncryptionType enum value which denotes the type of encryption/ decryption that this KeyCrypter
     * can understand.
     */
    public getUnderstoodEncryptionType(): EncryptionType {
        return EncryptionType.ENCRYPTED_SCRYPT_AES;
    }

    public toString(): string {
        return `AES-${KeyCrypterScrypt.KEY_LENGTH * 8}-CBC, Scrypt (N: ${this.scryptParameters.getN()})`;
    }

    public hashCode(): number {
        let hash = 17;
        const salt = this.scryptParameters.getSalt();
        for (const b of salt) {
            hash = 31 * hash + b;
        }
        hash = 31 * hash + this.scryptParameters.getN();
        hash = 31 * hash + this.scryptParameters.getR();
        hash = 31 * hash + this.scryptParameters.getP();
        return hash;
    }

    public equals(o: any): boolean {
        if (this === o) return true;
        if (o == null || !(o instanceof KeyCrypterScrypt)) return false;
        return this.scryptParameters.equals(o.scryptParameters);
    }
}