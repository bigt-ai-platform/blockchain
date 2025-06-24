import { Sha256Hash } from './Sha256Hash';
import { NetworkParameters } from './NetworkParameters';
import { Message } from './Message';

export class TransactionOutPoint extends Message {
    private hash: Sha256Hash;
    private index: number;

    constructor(params: NetworkParameters, payloadBytes?: Uint8Array, offset?: number) {
        super(params, payloadBytes, offset);
        this.hash = Sha256Hash.ZERO_HASH; // Initialize
        this.index = 0; // Initialize
        if (payloadBytes) {
            this.parse();
        }
    }

    protected parse(): void {
        this.cursor = this.offset;
        this.hash = this.readHash();
        this.index = this.readUint32();
        this.length = this.cursor - this.offset;
    }

    public bitcoinSerializeToStream(stream: any): void {
        stream.write(this.hash.getBytes());
        this.serializer.writeUint32(stream, this.index);
    }

    getHash(): Sha256Hash {
        return this.hash;
    }

    getIndex(): number {
        return this.index;
    }

    getMessageSize(): number {
        return this.length;
    }

    toString(): string {
        return `OutPoint: ${this.hash.toString()}:${this.index}`;
    }
}
