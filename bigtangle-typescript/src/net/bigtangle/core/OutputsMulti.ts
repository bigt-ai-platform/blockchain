import { Sha256Hash } from './Sha256Hash';

export class OutputsMulti {
    private hash: Sha256Hash | null = null;
    private toAddress: string | null = null;
    private outputIndex: number = 0;

    constructor(hash?: Sha256Hash, toAddress?: string, outputIndex?: number) {
        if (hash) this.hash = hash;
        if (toAddress) this.toAddress = toAddress;
        if (outputIndex !== undefined) this.outputIndex = outputIndex;
    }

    public getHash(): Sha256Hash | null {
        return this.hash;
    }

    public setHash(hash: Sha256Hash | null): void {
        this.hash = hash;
    }

    public getToAddress(): string | null {
        return this.toAddress;
    }

    public setToAddress(toAddress: string | null): void {
        this.toAddress = toAddress;
    }

    public getOutputIndex(): number {
        return this.outputIndex;
    }

    public setOutputIndex(outputIndex: number): void {
        this.outputIndex = outputIndex;
    }
}