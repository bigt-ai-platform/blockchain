
import { Buffer } from 'buffer';
import { Wallet } from '../../src/net/bigtangle/wallet/Wallet';
import { WalletExtension } from '../../src/net/bigtangle/wallet/WalletExtension';

export class FooWalletExtension implements WalletExtension {
    private readonly data: Buffer = Buffer.from([1, 2, 3]);
    private readonly isMandatory: boolean;
    private readonly id: string;

    public constructor(id: string, isMandatory: boolean) {
        this.isMandatory = isMandatory;
        this.id = id;
    }

    public getWalletExtensionID(): string {
        return this.id;
    }

    public isWalletExtensionMandatory(): boolean {
        return this.isMandatory;
    }

    public serializeWalletExtension(): Buffer {
        return this.data;
    }

    public deserializeWalletExtension(wallet: Wallet, data: Buffer): void {
        if (Buffer.compare(this.data, data) !== 0) {
            throw new Error('Invalid data');
        }
    }
}
