import { NetworkParameters } from './NetworkParameters';
import { Sha256Hash } from './Sha256Hash';
import { Buffer } from 'buffer';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class TransactionOutPoint {
    private params: NetworkParameters;
    private hash: Sha256Hash = Sha256Hash.ZERO_HASH;
    private index: number = 0;

    constructor(params: NetworkParameters, bytes?: Buffer, offset?: number) {
        this.params = params;
        
        if (bytes && offset !== undefined) {
            this.parse(bytes, offset);
        }
    }

    private parse(bytes: Buffer, offset: number): void {
        this.hash = Sha256Hash.wrapReversed(bytes.subarray(offset, offset + 32));
        offset += 32;
        this.index = bytes.readUInt32LE(offset);
    }

    public bitcoinSerialize(): Buffer {
        const out = new UnsafeByteArrayOutputStream();
        out.write(this.hash.getReversedBytes());
        
        const indexBuf = Buffer.alloc(4);
        indexBuf.writeUInt32LE(this.index, 0);
        out.write(indexBuf);
        
        return out.toBuffer();
    }

    public getMessageSize(): number {
        return 32 + 4; // 32 bytes for hash, 4 bytes for index
    }

    // Getters and setters
    public getHash(): Sha256Hash { return this.hash; }
    public setHash(hash: Sha256Hash): void { this.hash = hash; }
    
    public getIndex(): number { return this.index; }
    public setIndex(index: number): void { this.index = index; }
}
