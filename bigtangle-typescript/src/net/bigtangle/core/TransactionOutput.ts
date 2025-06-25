import { NetworkParameters } from './NetworkParameters';
import { VarInt } from './VarInt';
import { Buffer } from 'buffer';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class TransactionOutput {
    private params: NetworkParameters;
    private value: bigint = BigInt(0);
    private scriptBytes: Buffer = Buffer.alloc(0);

    constructor(params: NetworkParameters, bytes?: Buffer, offset?: number) {
        this.params = params;
        
        if (bytes && offset !== undefined) {
            this.parse(bytes, offset);
        }
    }

    private parse(bytes: Buffer, offset: number): void {
        this.value = bytes.readBigUInt64LE(offset);
        offset += 8;
        
        const scriptLength = VarInt.read(bytes, offset);
        offset += scriptLength.size;
        
        this.scriptBytes = bytes.subarray(offset, offset + scriptLength.value);
    }

    public bitcoinSerialize(): Buffer {
        const out = new UnsafeByteArrayOutputStream();
        
        // Serialize value
        const valueBuf = Buffer.alloc(8);
        valueBuf.writeBigUInt64LE(this.value, 0);
        out.write(valueBuf);
        
        // Serialize script
        VarInt.write(this.scriptBytes.length, out);
        out.write(this.scriptBytes);
        
        return out.toBuffer();
    }

    public getMessageSize(): number {
        let size = 8; // value
        size += VarInt.sizeOf(this.scriptBytes.length);
        size += this.scriptBytes.length;
        return size;
    }

    // Getters and setters
    public getValue(): bigint { return this.value; }
    public setValue(value: bigint): void { this.value = value; }
    
    public getScriptBytes(): Buffer { return this.scriptBytes; }
    public setScriptBytes(scriptBytes: Buffer): void { this.scriptBytes = scriptBytes; }
}
