import { KeyChainGroup } from './KeyChainGroup';
import { NetworkParameters } from '../core/NetworkParameters';
import { ServerPool } from '../pool/server/ServerPool';
import { ECKey } from '../core/ECKey';
import { KeyCrypter, KeyCrypterException, KeyParameter } from '../crypto/KeyCrypter';
import { KeyCrypterScrypt, ScryptParameters } from '../crypto/KeyCrypterScrypt';
import { TransactionSigner } from '../signers/TransactionSigner';
import { DecryptingKeyBag } from './DecryptingKeyBag';
import { MissingSigResolutionSigner } from '../signers/MissingSigResolutionSigner';
import { Key as ProtosKey } from './Protos';
import { RedeemData } from './RedeemData';
import { Transaction } from '../core/Transaction';
import { KeyBag } from './KeyBag';
import { EncryptionType } from '../crypto/EncryptableItem';
import { DeterministicKey } from '../crypto/DeterministicKey';

export abstract class WalletBase implements KeyBag {
    protected readonly lock = { lock: () => {}, unlock: () => {} };
    protected readonly keyChainGroupLock = { lock: () => {}, unlock: () => {} };

    protected serverPool: ServerPool | null = null;
    protected fee: boolean = true;
    protected static readonly SPENTPENDINGTIMEOUT = 120000;

    protected keyChainGroup!: KeyChainGroup;
    public params!: NetworkParameters;
    protected version: number = 0;
    protected signers: TransactionSigner[] = [];
	 
    public getNetworkParameters(): NetworkParameters {
        return this.params;
    }

    public addTransactionSigner(signer: TransactionSigner): void {
        this.lock.lock();
        try {
            if (signer.isReady()) {
                this.signers.push(signer);
            } else {
                throw new Error(`Signer instance is not ready to be added into Wallet: ${signer.constructor.name}`);
            }
        } finally {
            this.lock.unlock();
        }
    }

    public getTransactionSigners(): TransactionSigner[] {
        this.lock.lock();
        try {
            return [...this.signers];
        } finally {
            this.lock.unlock();
        }
    }

    public removeKey(key: ECKey): boolean {
        this.keyChainGroupLock.lock();
        try {
            return this.keyChainGroup.removeImportedKey(key);
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public getImportedKeys(): ECKey[] {
        this.keyChainGroupLock.lock();
        try {
            return this.keyChainGroup.getImportedKeys();
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public importKey(key: ECKey): boolean {
        return this.importKeys([key]) === 1;
    }

    public importKeys(keys: ECKey[]): number {
        this.keyChainGroupLock.lock();
        let result: number;
        try {
            this.checkNoDeterministicKeys(keys);
            result = this.keyChainGroup.importKeys(keys);
        } finally {
            this.keyChainGroupLock.unlock();
        }
        return result;
    }

    private checkNoDeterministicKeys(keys: ECKey[]): void {
        for (const key of keys) {
            if (key instanceof DeterministicKey) {
                throw new Error("Cannot import HD keys back into the wallet");
            }
        }
    }

    public importKeysAndEncrypt(keys: ECKey[], password: string): number {
        this.keyChainGroupLock.lock();
        let result: number;
        try {
            if (!this.getKeyCrypter()) {
                throw new Error("Wallet is not encrypted");
            }
            result = this.importKeysAndEncryptWithAesKey(keys, this.getKeyCrypter()!.deriveKey(password));
        } finally {
            this.keyChainGroupLock.unlock();
        }
        return result;
    }

    public importKeysAndEncryptWithAesKey(keys: ECKey[], aesKey: KeyParameter): number {
        this.keyChainGroupLock.lock();
        try {
            this.checkNoDeterministicKeys(keys);
            return this.keyChainGroup.importKeysAndEncrypt(keys, aesKey);
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public findKeyFromPubHash(pubkeyHash: Uint8Array): ECKey | null {
        this.keyChainGroupLock.lock();
        try {
            return this.keyChainGroup.findKeyFromPubHash(pubkeyHash);
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public findKeyFromPubKey(pubkey: Uint8Array): ECKey | null {
        this.keyChainGroupLock.lock();
        try {
            return this.keyChainGroup.findKeyFromPubKey(pubkey);
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public findRedeemDataFromScriptHash(payToScriptHash: Uint8Array): RedeemData | null {
        this.keyChainGroupLock.lock();
        try {
            return this.keyChainGroup.findRedeemDataFromScriptHash(payToScriptHash);
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public encrypt(password: string): void {
        this.keyChainGroupLock.lock();
        try {
            const scrypt = new KeyCrypterScrypt();
            this.keyChainGroup.encrypt(scrypt, scrypt.deriveKey(password));
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public encryptWithKey(keyCrypter: KeyCrypter, aesKey: KeyParameter): void {
        this.keyChainGroupLock.lock();
        try {
            this.keyChainGroup.encrypt(keyCrypter, aesKey);
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public decrypt(password: string): void {
        this.keyChainGroupLock.lock();
        try {
            const crypter = this.keyChainGroup.getKeyCrypter();
            if (!crypter) {
                throw new Error("Not encrypted");
            }
            this.keyChainGroup.decrypt(crypter.deriveKey(password));
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public decryptWithKey(aesKey: KeyParameter): void {
        this.keyChainGroupLock.lock();
        try {
            this.keyChainGroup.decrypt(aesKey);
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public getKeyCrypter(): KeyCrypter | null {
        this.keyChainGroupLock.lock();
        try {
            return this.keyChainGroup.getKeyCrypter();
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public getEncryptionType(): EncryptionType {
        this.keyChainGroupLock.lock();
        try {
            const crypter = this.keyChainGroup.getKeyCrypter();
            if (crypter !== null) {
                return crypter.getUnderstoodEncryptionType();
            } else {
                return EncryptionType.UNENCRYPTED;
            }
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public isEncrypted(): boolean {
        return this.getEncryptionType() !== EncryptionType.UNENCRYPTED;
    }

    protected serializeKeyChainGroupToProtobuf(): ProtosKey[] {
        this.keyChainGroupLock.lock();
        try {
            return this.keyChainGroup.serializeToProtobuf();
        } finally {
            this.keyChainGroupLock.unlock();
        }
    }

    public saveToFile(temp: File, destFile: File): void {
        // Not implemented for now
    }

    public saveTo(stream: any): void {
        // Not implemented for now
    }

    public abstract saveToFileStream(f: any): void;

    public getParams(): NetworkParameters {
        return this.params;
    }

    public getVersion(): number {
        return this.version;
    }

    public setVersion(version: number): void {
        this.version = version;
    }

    public signTransaction(tx: Transaction, aesKey: KeyParameter, missingSigsMode: any): void {
        this.lock.lock();
        try {
            const inputs = tx.getInputs();
            const outputs = tx.getOutputs();
            if (inputs.length === 0 || outputs.length === 0) {
                throw new Error("Transaction must have inputs and outputs");
            }

            const maybeDecryptingKeyBag = new DecryptingKeyBag(this, aesKey);

            for (const txIn of inputs) {
                const txOut = txIn.getConnectedOutput();
                if (txOut === null) {
                    continue;
                }
            }

            const proposal = new TransactionSigner.ProposedTransaction(tx);
            for (const signer of this.signers) {
                if (!signer.signInputs(proposal, maybeDecryptingKeyBag)) {
                    // Remove WalletBase.log usage, fallback to console
                    console.info(`${signer.constructor.name} returned false for the tx`);
                }
            }

            new MissingSigResolutionSigner(missingSigsMode).signInputs(proposal, maybeDecryptingKeyBag);
        } finally {
            this.lock.unlock();
        }
    }

    public signTransactionWithAesKey(tx: Transaction, aesKey: KeyParameter): void {
        // Use string literal for missingSigsMode, or pass null/undefined if not used
        this.signTransaction(tx, aesKey, 'THROW');
    }

    public walletKeys(aesKey: KeyParameter | null): ECKey[] {
        const maybeDecryptingKeyBag = new DecryptingKeyBag(this, aesKey);
        const walletKeys: ECKey[] = [];
        for (const key of this.getImportedKeys()) {
            const ecKey = maybeDecryptingKeyBag.maybeDecrypt(key);
            if (ecKey) {
                walletKeys.push(ecKey);
            }
        }
        // Remove getDeterministicKeyChains usage if not present on KeyChainGroup
        return walletKeys;
    }

    public walletKeysWithoutAesKey(): ECKey[] {
        return this.walletKeys(null);
    }

    public setServerURL(contextRoot: string): void {
        this.serverPool = new ServerPool(this.params, [contextRoot]);
    }

    public getFee(): boolean {
        return this.fee;
    }

    public setFee(fee: boolean): void {
        this.fee = fee;
    }

    public getServerURL(): string {
        this.serverPool ??= new ServerPool(this.params);
        return this.serverPool.getServer().getServerurl()!;
    }

    public setServerPool(serverPool: ServerPool): void {
        this.serverPool = serverPool;
    }

    public getKeyChainGroup(): KeyChainGroup {
        return this.keyChainGroup;
    }
}
