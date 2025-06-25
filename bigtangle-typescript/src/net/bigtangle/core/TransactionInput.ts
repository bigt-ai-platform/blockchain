import { NetworkParameters } from './NetworkParameters';
import { TransactionOutPoint } from './TransactionOutPoint';
import { Script } from './Script';
import { VarInt } from './VarInt';
import { Buffer } from 'buffer';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class TransactionInput {
    private params: NetworkParameters;
    private outpoint: TransactionOutPoint;
    private scriptBytes: Buffer = Buffer.alloc(0);
    private sequence: number = 0;

    constructor(params: NetworkParameters, bytes?: Buffer, offset?: number) {
        this.params = params;
        this.outpoint = new TransactionOutPoint(params);
        
        if (bytes && offset !== undefined) {
            this.parse(bytes, offset);
        }
    }

    private parse(bytes: Buffer, offset: number): void {
        this.outpoint = new TransactionOutPoint(this.params, bytes, offset);
        offset += this.outpoint.getMessageSize();
        
        const scriptLength = VarInt.read(bytes, offset);
        offset += scriptLength.size;
        
        this.scriptBytes = bytes.subarray(offset, offset + scriptLength.value);
        offset += scriptLength.value;
        
        this.sequence = bytes.readUInt32LE(offset);
    }

    public bitcoinSerialize(): Buffer {
        const out = new UnsafeByteArrayOutputStream();
        
        // Serialize outpoint
        const outpointBuf = this.outpoint.bitcoinSerialize();
        out.write(outpointBuf);
        
        // Serialize script
        VarInt.write(this.scriptBytes.length, out);
        out.write(this.scriptBytes);
        
        // Serialize sequence
        const seqBuf = Buffer.alloc(4);
        seqBuf.writeUInt32LE(this.sequence, 0);
        out.write(seqBuf);
        
        return out.toBuffer();
    }

    public getMessageSize(): number {
        let size = this.outpoint.getMessageSize();
        size += VarInt.sizeOf(this.scriptBytes.length);
        size += this.scriptBytes.length;
        size += 4; // sequence
        return size;
    }

    // Getters and setters
    public getOutpoint(): TransactionOutPoint { return this.outpoint; }
    public setOutpoint(outpoint: TransactionOutPoint): void { this.outpoint = outpoint; }
    
    public getScriptBytes(): Buffer { return this.scriptBytes; }
    public setScriptBytes(scriptBytes: Buffer): void { this.scriptBytes = scriptBytes; }
    
    public getSequence(): number { return this.sequence; }
    public setSequence(sequence: number): void { this.sequence = sequence; }
}
