import { MnemonicCode } from '../crypto/MnemonicCode';
import { MnemonicException } from '../crypto/MnemonicException';
import { KeyCrypter, KeyParameter } from '../crypto/KeyCrypter';
import { EncryptedData } from '../crypto/EncryptedData';
import { EncryptionType, EncryptableItem } from '../crypto/EncryptableItem';
import { UnreadableWalletException } from './UnreadableWalletException';
import { Utils } from '../utils/Utils';

/**
 * Holds the seed bytes for the BIP32 deterministic wallet algorithm, inside a
 * {@link DeterministicKeyChain}. The purpose of this wrapper is to simplify the encryption
 * code.
 */
export class DeterministicSeed implements EncryptableItem {
    public static readonly DEFAULT_SEED_ENTROPY_BITS = 128;
    public static readonly MAX_SEED_ENTROPY_BITS = 512;

    private seedBytes: Uint8Array | null = null;
    private mnemonicCode: string[] | null = null;
    private encryptedMnemonicCode: EncryptedData | null = null;
    private encryptedSeed: EncryptedData | null = null;
    private creationTimeSeconds: number = 0;

    constructor(mnemonicCode: string, seed: Uint8Array, passphrase: string, creationTimeSeconds: number);
    constructor(seed: Uint8Array, mnemonic: string[], creationTimeSeconds: number);
    constructor(encryptedMnemonic: EncryptedData, encryptedSeed: EncryptedData | null, creationTimeSeconds: number);
    constructor(mnemonicCode: string[], seed: Uint8Array | null, passphrase: string, creationTimeSeconds: number);
    constructor(random: any, bits: number, passphrase: string, creationTimeSeconds: number);
    constructor(entropy: Uint8Array, passphrase: string, creationTimeSeconds: number);
    constructor(...args: any[]) {
        if (typeof args[0] === 'string') {
            // mnemonicCode: string, seed: Uint8Array, passphrase: string, creationTimeSeconds: number
            this.mnemonicCode = DeterministicSeed.decodeMnemonicCode(args[0]);
            this.seedBytes = args[1];
            this.creationTimeSeconds = args[3];
        } else if (args[0] instanceof Uint8Array && Array.isArray(args[1])) {
            // seed: Uint8Array, mnemonic: string[], creationTimeSeconds: number
            this.seedBytes = args[0];
            this.mnemonicCode = args[1];
            this.creationTimeSeconds = args[2];
        } else if (args[0] instanceof EncryptedData) {
            // encryptedMnemonic: EncryptedData, encryptedSeed: EncryptedData | null, creationTimeSeconds: number
            this.encryptedMnemonicCode = args[0];
            this.encryptedSeed = args[1];
            this.creationTimeSeconds = args[2];
        } else if (Array.isArray(args[0])) {
            // mnemonicCode: string[], seed: Uint8Array | null, passphrase: string, creationTimeSeconds: number
            const mnemonicCodeList = args[0];
            const seedBytesArg = args[1];
            const passphrase = args[2];
            const creationTimeSeconds = args[3];

            this.mnemonicCode = mnemonicCodeList;
            this.seedBytes = seedBytesArg || MnemonicCode.toSeed(mnemonicCodeList, passphrase);
            this.creationTimeSeconds = creationTimeSeconds;
        } else if (typeof args[0] === 'object' && args[0].constructor.name === 'SecureRandom') { // SecureRandom
            // random: any, bits: number, passphrase: string, creationTimeSeconds: number
            const random = args[0];
            const bits = args[1];
            const passphrase = args[2];
            const creationTimeSeconds = args[3];
            const entropy = DeterministicSeed.getEntropy(random, bits);
            this.mnemonicCode = MnemonicCode.INSTANCE.toMnemonic(entropy);
            this.seedBytes = MnemonicCode.toSeed(this.mnemonicCode, passphrase);
            this.creationTimeSeconds = creationTimeSeconds;
        } else if (args[0] instanceof Uint8Array) { // entropy
            // entropy: Uint8Array, passphrase: string, creationTimeSeconds: number
            const entropy = args[0];
            const passphrase = args[1];
            const creationTimeSeconds = args[2];
            if (entropy.length % 4 !== 0) throw new Error("entropy size in bits not divisible by 32");
            if (entropy.length * 8 < DeterministicSeed.DEFAULT_SEED_ENTROPY_BITS) throw new Error("entropy size too small");

            this.mnemonicCode = MnemonicCode.INSTANCE.toMnemonic(entropy);
            this.seedBytes = MnemonicCode.toSeed(this.mnemonicCode, passphrase);
            this.creationTimeSeconds = creationTimeSeconds;
        } else {
            throw new Error("Invalid constructor arguments");
        }
    }

    private static getEntropy(random: any, bits: number): Uint8Array {
        if (bits > DeterministicSeed.MAX_SEED_ENTROPY_BITS) throw new Error("requested entropy size too large");
        const seed = new Uint8Array(bits / 8);
        // Assuming random.nextBytes fills the array with random bytes
        // For browser/Node.js, use crypto.getRandomValues or similar
        Utils.randomBytes(bits / 8).forEach((byte, i) => seed[i] = byte);
        return seed;
    }

    public isEncrypted(): boolean {
        if (this.mnemonicCode === null && this.encryptedMnemonicCode === null) {
            throw new Error("MnemonicCode and EncryptedMnemonicCode are both null");
        }
        return this.encryptedMnemonicCode !== null;
    }

    public toString(): string {
        return this.isEncrypted()
            ? "DeterministicSeed [encrypted]"
            : `DeterministicSeed ${this.toHexString()} ${this.mnemonicCode ? this.mnemonicCode.join(" ") : ""}`;
    }

    public toHexString(): string | null {
        return this.seedBytes !== null ? Utils.HEX.encode(this.seedBytes) : null;
    }

    public getSecretBytes(): Uint8Array | null {
        return this.getMnemonicAsBytes();
    }

    public getSeedBytes(): Uint8Array | null {
        return this.seedBytes;
    }

    public getEncryptedData(): EncryptedData | null {
        return this.encryptedMnemonicCode;
    }

    public getEncryptionType(): EncryptionType {
        return EncryptionType.ENCRYPTED_SCRYPT_AES;
    }

    public getEncryptedSeedData(): EncryptedData | null {
        return this.encryptedSeed;
    }

    public getCreationTimeSeconds(): number {
        return this.creationTimeSeconds;
    }

    public setCreationTimeSeconds(creationTimeSeconds: number): void {
        this.creationTimeSeconds = creationTimeSeconds;
    }

    public encrypt(keyCrypter: KeyCrypter, aesKey: KeyParameter): DeterministicSeed {
        if (this.encryptedMnemonicCode !== null) throw new Error("Trying to encrypt seed twice");
        if (this.mnemonicCode === null) throw new Error("Mnemonic missing so cannot encrypt");
        const encryptedMnemonic = keyCrypter.encrypt(this.getMnemonicAsBytes()!, aesKey);
        const encryptedSeed = keyCrypter.encrypt(this.seedBytes!, aesKey);
        return new DeterministicSeed(encryptedMnemonic, encryptedSeed, this.creationTimeSeconds);
    }

    private getMnemonicAsBytes(): Uint8Array | null {
        return this.mnemonicCode ? new TextEncoder().encode(this.mnemonicCode.join(" ")) : null;
    }

    public decrypt(crypter: KeyCrypter, passphrase: string, aesKey: KeyParameter): DeterministicSeed {
        if (!this.isEncrypted()) throw new Error("Seed is not encrypted");
        if (this.encryptedMnemonicCode === null) throw new Error("Encrypted mnemonic code is null");
        const mnemonic = DeterministicSeed.decodeMnemonicCode(crypter.decrypt(this.encryptedMnemonicCode, aesKey));
        const seed = this.encryptedSeed === null ? null : crypter.decrypt(this.encryptedSeed, aesKey);
        return new DeterministicSeed(mnemonic, seed, passphrase, this.creationTimeSeconds);
    }

    public equals(o: any): boolean {
        if (this === o) return true;
        if (o === null || !(o instanceof DeterministicSeed)) return false;
        const other = o as DeterministicSeed;
        return this.creationTimeSeconds === other.creationTimeSeconds &&
               (this.encryptedMnemonicCode === other.encryptedMnemonicCode || (this.encryptedMnemonicCode !== null && other.encryptedMnemonicCode !== null && this.encryptedMnemonicCode.equals(other.encryptedMnemonicCode))) &&
               (this.mnemonicCode === other.mnemonicCode || (this.mnemonicCode !== null && other.mnemonicCode !== null && this.mnemonicCode.every((val, i) => val === other.mnemonicCode![i])));
    }

    public hashCode(): number {
        let result = 17;
        result = 31 * result + this.creationTimeSeconds;
        result = 31 * result + (this.encryptedMnemonicCode ? this.encryptedMnemonicCode.hashCode() : 0);
        result = 31 * result + (this.mnemonicCode ? this.mnemonicCode.reduce((acc, val) => acc + val.length, 0) : 0); // Simple hash for array
        return result;
    }

    public check(): void {
        if (this.mnemonicCode !== null) {
            MnemonicCode.INSTANCE.check(this.mnemonicCode);
        }
    }

    public getEntropyBytes(): Uint8Array {
        if (this.mnemonicCode === null) throw new Error("Mnemonic code is null");
        return MnemonicCode.INSTANCE.toEntropy(this.mnemonicCode);
    }

    public getMnemonicCode(): string[] | null {
        return this.mnemonicCode;
    }

    private static decodeMnemonicCode(mnemonicCode: Uint8Array | string): string[] {
        const mnemonicString = typeof mnemonicCode === 'string' ? mnemonicCode : new TextDecoder('utf-8').decode(mnemonicCode);
        return mnemonicString.split(" ");
    }
}
