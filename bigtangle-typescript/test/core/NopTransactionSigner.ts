
import { Buffer } from 'buffer';
import { TransactionSigner } from '../../src/net/bigtangle/signers/TransactionSigner';
import { ProposedTransaction } from '../../src/net/bigtangle/core/ProposedTransaction';
import { KeyBag } from '../../src/net/bigtangle/wallet/KeyBag';

export class NopTransactionSigner implements TransactionSigner {
    private isReady: boolean;

    public constructor(ready?: boolean) {
        this.isReady = ready || false;
    }

    public isReadyToSign(): boolean {
        return this.isReady;
    }

    public serialize(): Buffer {
        return this.isReady ? Buffer.from([1]) : Buffer.from([0]);
    }

    public deserialize(data: Buffer): void {
        if (data.length > 0) this.isReady = data[0] === 1;
    }

    public signInputs(t: ProposedTransaction, keyBag: KeyBag): boolean {
        return false;
    }
}
