import { NetworkParameters } from './NetworkParameters';
import { Message } from './Message';
import { TransactionOutPoint } from './TransactionOutPoint';
import { Script } from './Script';
import { ECKey } from './ECKey';
import { Utils } from './Utils';
import { Sha256Hash } from './Sha256Hash';
import { VarInt } from './VarInt';
import { Buffer } from 'buffer';
import { BitcoinSerializer } from './BitcoinSerializer';

export class TransactionInput extends Message {
    private outpoint: TransactionOutPoint;
    private scriptBytes: Buffer;
    private sequence: number;

    constructor(params: NetworkParameters);
    constructor(params: NetworkParameters, outpoint: TransactionOutPoint, scriptBytes: Buffer, sequence: number);
    constructor(params: NetworkParameters, payload: Buffer, offset: number, length?: number);
    constructor(params: NetworkParameters, arg1?: TransactionOutPoint | Buffer, arg2?: Buffer | number, arg3?: number, arg4?: number) {
        super(params);
        
        if (arg1 instanceof TransactionOutPoint) {
            // Second form: (params, outpoint, scriptBytes, sequence)
            this.outpoint = arg1;
            this.scriptBytes = arg2 as Buffer;
            this.sequence = arg3 as number;
        } else if (arg1 instanceof Buffer) {
            // Third form: (params, payload, offset, length)
            this.outpoint = new TransactionOutPoint(this.params);
            this.scriptBytes = Buffer.alloc(0);
            this.sequence = 0;
            this.parse(arg1, arg2 as number, arg3);
        } else {
            // First form: (params)
            this.outpoint = new TransactionOutPoint(this.params);
            this.scriptBytes = Buffer.alloc(0);
            this.sequence = 0xffffffff; // Default sequence
        }
    }
    
    protected parse(payload: Buffer, offset: number, length?: number): void {
        // TODO: Implement actual parsing
        // For now, just set sequence from last 4 bytes
        this.sequence = payload.readUInt32LE(payload.length - 4);
    }

    getOutpoint(): TransactionOutPoint {
        return this.outpoint;
    }

    getScriptBytes(): Buffer {
        return this.scriptBytes;
    }

    bitcoinSerialize(): Buffer {
        const buffer = Buffer.alloc(this.getMessageSize());
        const stream = new BitcoinSerializer(buffer);
        this.bitcoinSerializeToStream(stream);
        return buffer;
    }

    public getMessageSize(): number {
        let size = this.outpoint.getMessageSize();
        size += VarInt.sizeOf(this.scriptBytes.length) + this.scriptBytes.length;
        size += 4; // sequence
        return size;
    }

    public bitcoinSerializeToStream(stream: BitcoinSerializer): void {
        this.outpoint.bitcoinSerializeToStream(stream);
        stream.writeVarInt(this.scriptBytes.length);
        stream.write(this.scriptBytes);
        stream.writeUint32(this.sequence);
    }
}
