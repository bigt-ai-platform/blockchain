import { TransactionSigner } from './TransactionSigner';

/**
 * A signer that doesn't have any state to be serialized.
 */
export abstract class StatelessTransactionSigner implements TransactionSigner {
    public deserialize(data: Uint8Array): void {
    }

    public serialize(): Uint8Array {
        return new Uint8Array(0);
    }

    public abstract isReady(): boolean;
    public abstract signInputs(propTx: any, keyBag: any): boolean;
}
