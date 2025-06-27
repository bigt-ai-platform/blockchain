import { Buffer } from 'buffer';
import { KeyChain, KeyPurpose } from './KeyChain';
import { ECKey } from '../core/ECKey';
import { KeyCrypter } from '../crypto/KeyCrypter';
import { KeyCrypterException } from '../crypto/KeyCrypterException';
import { KeyCrypterScrypt } from '../crypto/KeyCrypterScrypt';
import { BloomFilter } from '../core/BloomFilter';
import { EncryptableKeyChain } from './EncryptableKeyChain';

export class BasicKeyChain implements EncryptableKeyChain {
    private hashToKeys: Map<string, ECKey>;
    private pubkeyToKeys: Map<string, ECKey>;
    private keyCrypter: KeyCrypter | null;
    private isWatching: boolean;

    public constructor(crypter?: KeyCrypter) {
        this.keyCrypter = crypter || null;
        this.hashToKeys = new Map<string, ECKey>();
        this.pubkeyToKeys = new Map<string, ECKey>();
        this.isWatching = false;
    }

    public getKeyCrypter(): KeyCrypter | null {
        return this.keyCrypter;
    }

    public getKey(purpose?: KeyPurpose): ECKey {
        if (this.hashToKeys.size === 0) {
            if (this.keyCrypter !== null) {
                throw new Error('Cannot create a key in an encrypted keychain');
            }
            const key = new ECKey();
            this.importKeyLocked(key);
        }
        return this.hashToKeys.values().next().value;
    }

    public getKeys(purpose?: KeyPurpose, numberOfKeys?: number): ECKey[] {
        if (numberOfKeys === undefined) {
            return Array.from(this.hashToKeys.values());
        }
        if (this.hashToKeys.size < numberOfKeys) {
            if (this.keyCrypter !== null) {
                throw new Error('Cannot create keys in an encrypted keychain');
            }
            const keys: ECKey[] = [];
            for (let i = 0; i < numberOfKeys - this.hashToKeys.size; i++) {
                keys.push(new ECKey());
            }
            this.importKeysLocked(keys);
        }
        const keysToReturn: ECKey[] = [];
        let count = 0;
        const iterator = this.hashToKeys.values();
        let result = iterator.next();
        while (!result.done && count < numberOfKeys) {
            keysToReturn.push(result.value);
            count++;
            result = iterator.next();
        }
        return keysToReturn;
    }

    public importKeys(...keys: ECKey[]): number {
        for (const key of keys) {
            this.checkKeyEncryptionStateMatches(key);
        }
        let actuallyAdded = 0;
        for (const key of keys) {
            if (this.hasKey(key)) {
                continue;
            }
            this.importKeyLocked(key);
            actuallyAdded++;
        }
        return actuallyAdded;
    }

    private checkKeyEncryptionStateMatches(key: ECKey): void {
        if (this.keyCrypter === null && key.isEncrypted()) {
            throw new KeyCrypterException('Key is encrypted but chain is not');
        } else if (this.keyCrypter !== null && !key.isEncrypted()) {
            throw new KeyCrypterException('Key is not encrypted but chain is');
        } else if (
            this.keyCrypter !== null &&
            key.getKeyCrypter() !== null &&
            !this.keyCrypter.equals(key.getKeyCrypter())
        ) {
            throw new KeyCrypterException(
                'Key encrypted under different parameters to chain',
            );
        }
    }

    private importKeyLocked(key: ECKey): void {
        if (this.hashToKeys.size === 0) {
            this.isWatching = key.isWatching();
        } else {
            if (key.isWatching() && !this.isWatching) {
                throw new Error('Key is watching but chain is not');
            }
            if (!key.isWatching() && this.isWatching) {
                throw new Error('Key is not watching but chain is');
            }
        }
        this.pubkeyToKeys.set(key.getPubKey().toString('hex'), key);
        this.hashToKeys.set(key.getPubKeyHash().toString('hex'), key);
    }

    private importKeysLocked(keys: ECKey[]): void {
        for (const key of keys) {
            this.importKeyLocked(key);
        }
    }

    public importKey(key: ECKey): void {
        this.checkKeyEncryptionStateMatches(key);
        if (this.hasKey(key)) {
            return;
        }
        this.importKeyLocked(key);
    }

    public findKeyFromPubHash(pubkeyHash: Buffer): ECKey | null {
        return this.hashToKeys.get(pubkeyHash.toString('hex')) || null;
    }

    public findKeyFromPubKey(pubkey: Buffer): ECKey | null {
        return this.pubkeyToKeys.get(pubkey.toString('hex')) || null;
    }

    public hasKey(key: ECKey): boolean {
        return this.findKeyFromPubKey(key.getPubKey()) !== null;
    }

    public numKeys(): number {
        return this.pubkeyToKeys.size;
    }

    public isWatchingState(): 'EMPTY' | 'WATCHING' | 'REGULAR' {
        if (this.hashToKeys.size === 0) {
            return 'EMPTY';
        }
        return this.isWatching ? 'WATCHING' : 'REGULAR';
    }

    public removeKey(key: ECKey): boolean {
        const a = this.hashToKeys.delete(key.getPubKeyHash().toString('hex'));
        const b = this.pubkeyToKeys.delete(key.getPubKey().toString('hex'));
        if (a !== b) {
            throw new Error('Inconsistent state');
        }
        return a;
    }

    public getEarliestKeyCreationTime(): number {
        let time = Number.MAX_SAFE_INTEGER;
        for (const key of this.hashToKeys.values()) {
            time = Math.min(key.getCreationTimeSeconds(), time);
        }
        return time;
    }

    public toEncrypted(password: string): BasicKeyChain;
    public toEncrypted(keyCrypter: KeyCrypter, aesKey: Buffer): BasicKeyChain;
    public toEncrypted(
        passwordOrKeyCrypter: string | KeyCrypter,
        aesKey?: Buffer,
    ): BasicKeyChain {
        if (typeof passwordOrKeyCrypter === 'string') {
            const password = passwordOrKeyCrypter;
            if (password === null || password.length === 0) {
                throw new Error('Password cannot be empty');
            }
            const scrypt = new KeyCrypterScrypt();
            const derivedKey = scrypt.deriveKey(password);
            return this.toEncrypted(scrypt, derivedKey);
        } else {
            const keyCrypter = passwordOrKeyCrypter;
            if (this.keyCrypter !== null) {
                throw new Error('Key chain is already encrypted');
            }
            const encrypted = new BasicKeyChain(keyCrypter);
            for (const key of this.hashToKeys.values()) {
                const encryptedKey = key.encrypt(keyCrypter, aesKey);
                if (
                    !ECKey.encryptionIsReversible(key, encryptedKey, keyCrypter, aesKey)
                ) {
                    throw new KeyCrypterException(
                        `The key ${key.toString()} cannot be successfully decrypted after encryption so aborting wallet encryption.`,
                    );
                }
                encrypted.importKeyLocked(encryptedKey);
            }
            return encrypted;
        }
    }

    public toDecrypted(password: string): BasicKeyChain;
    public toDecrypted(aesKey: Buffer): BasicKeyChain;
    public toDecrypted(passwordOrAesKey: string | Buffer): BasicKeyChain {
        if (typeof passwordOrAesKey === 'string') {
            const password = passwordOrAesKey;
            if (this.keyCrypter === null) {
                throw new Error('Wallet is already decrypted');
            }
            const aesKey = this.keyCrypter.deriveKey(password);
            return this.toDecrypted(aesKey);
        } else {
            const aesKey = passwordOrAesKey;
            if (this.keyCrypter === null) {
                throw new Error('Wallet is already decrypted');
            }
            if (this.numKeys() > 0 && !this.checkAESKey(aesKey)) {
                throw new KeyCrypterException('Password/key was incorrect.');
            }
            const decrypted = new BasicKeyChain();
            for (const key of this.hashToKeys.values()) {
                decrypted.importKeyLocked(key.decrypt(aesKey));
            }
            return decrypted;
        }
    }

    public checkPassword(password: string): boolean {
        if (this.keyCrypter === null) {
            throw new Error('Key chain not encrypted');
        }
        return this.checkAESKey(this.keyCrypter.deriveKey(password));
    }

    public checkAESKey(aesKey: Buffer): boolean {
        if (this.hashToKeys.size === 0) {
            return false;
        }
        if (this.keyCrypter === null) {
            throw new Error('Key chain is not encrypted');
        }
        let first: ECKey | null = null;
        for (const key of this.hashToKeys.values()) {
            if (key.isEncrypted()) {
                first = key;
                break;
            }
        }
        if (first === null) {
            throw new Error('No encrypted keys in the wallet');
        }
        try {
            const rebornKey = first.decrypt(aesKey);
            return Buffer.compare(first.getPubKey(), rebornKey.getPubKey()) === 0;
        } catch (e) {
            return false;
        }
    }

    public getFilter(
        size: number,
        falsePositiveRate: number,
        tweak: number,
    ): BloomFilter {
        const filter = new BloomFilter(size, falsePositiveRate, tweak);
        for (const key of this.hashToKeys.values()) {
            filter.insert(key.getPubKey());
            filter.insert(key.getPubKeyHash());
        }
        return filter;
    }

    public numBloomFilterEntries(): number {
        return this.numKeys() * 2;
    }

    public findOldestKeyAfter(timeSecs: number): ECKey | null {
        let oldest: ECKey | null = null;
        for (const key of this.hashToKeys.values()) {
            const keyTime = key.getCreationTimeSeconds();
            if (keyTime > timeSecs) {
                if (oldest === null || oldest.getCreationTimeSeconds() > keyTime) {
                    oldest = key;
                }
            }
        }
        return oldest;
    }

    public findKeysBefore(timeSecs: number): ECKey[] {
        const results: ECKey[] = [];
        for (const key of this.hashToKeys.values()) {
            const keyTime = key.getCreationTimeSeconds();
            if (keyTime < timeSecs) {
                results.push(key);
            }
        }
        return results;
    }
}
