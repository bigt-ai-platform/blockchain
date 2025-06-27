import { Transaction } from '../core/Transaction';
import { ChildNumber } from '../crypto/ChildNumber';
import { Script } from '../script/Script';
import { KeyBag } from '../wallet/KeyBag'; // Placeholder

export namespace TransactionSigner {
    /**
     * This class wraps transaction proposed to complete keeping a metadata that may be updated, used and effectively
     * shared by transaction signers.
     */
    export class ProposedTransaction {
        public readonly partialTx: Transaction;
        public readonly keyPaths: Map<Script, ChildNumber[]>;

        constructor(partialTx: Transaction) {
            this.partialTx = partialTx;
            this.keyPaths = new Map<Script, ChildNumber[]>();
        }
    }

    export class MissingSignatureException extends Error {
        constructor(message?: string) {
            super(message);
            this.name = "MissingSignatureException";
            Object.setPrototypeOf(this, MissingSignatureException.prototype);
        }
    }
}

/**
 * <p>Implementations of this interface are intended to sign inputs of the given transaction. Given transaction may already
 * be partially signed or somehow altered by other signers.</p>
 * <p>To make use of the signer, you need to add it into the  wallet by
 * calling {@link net.bigtangle.wallet.Wallet#addTransactionSigner(TransactionSigner)}. Signer will be serialized
 * along with the wallet data. In order for a wallet to recreate signer after deserialization, each signer
 * should have no-args constructor</p>
 */
export interface TransactionSigner {
    /**
     * Returns true if this signer is ready to be used.
     */
    isReady(): boolean;

    /**
     * Returns byte array of data representing state of this signer. It's used to serialize/deserialize this signer
     */
    serialize(): Uint8Array;

    /**
     * Uses given byte array of data to reconstruct internal state of this signer
     */
    deserialize(data: Uint8Array): void;

    /**
     * Signs given transaction's inputs.
     * Returns true if signer is compatible with given transaction (can do something meaningful with it).
     * Otherwise this method returns false
     */
    signInputs(propTx: TransactionSigner.ProposedTransaction, keyBag: KeyBag): boolean;
}