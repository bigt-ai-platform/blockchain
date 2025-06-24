import { NetworkParameters } from './NetworkParameters';
import { Message } from './Message';
import { Sha256Hash } from './Sha256Hash';
import { TransactionInput } from './TransactionInput';
import { TransactionOutput } from './TransactionOutput';
import { Coin } from './Coin';
import { ECKey } from './ECKey';
import { Script } from './Script';
import { ScriptBuilder } from './ScriptBuilder';
import { VarInt } from './VarInt';
import { Utils } from './Utils';
import { Buffer } from 'buffer';
import { BitcoinSerializer } from './BitcoinSerializer';

export class Transaction extends Message {
    private version: number;
    private inputs: TransactionInput[];
    private outputs: TransactionOutput[];
    private lockTime: number;
    private hash: Sha256Hash | null;

    constructor(params: NetworkParameters);
    constructor(params: NetworkParameters, payload: Buffer, offset: number, length?: number);
    constructor(params: NetworkParameters, arg1?: Buffer, offset?: number, length?: number) {
        super(params);
        
        if (arg1 === undefined) {
            // First form: (params)
            this.version = 1;
            this.inputs = [];
            this.outputs = [];
            this.lockTime = 0;
            this.hash = null;
        } else {
            // Second form: (params, payload, offset, length)
            this.version = 1;
            this.inputs = [];
            this.outputs = [];
            this.lockTime = 0;
            this.hash = null;
            this.parse(arg1, offset!, length);
        }
    }
    
    protected parse(payload: Buffer, offset: number, length?: number): void {
        // TODO: Implement actual parsing
        // For now, just set the version from the first 4 bytes
        this.version = payload.readUInt32LE(offset);
    }

    getHash(): Sha256Hash {
        if (this.hash === null) {
            const serialized = this.bitcoinSerialize();
            this.hash = Sha256Hash.wrapReversed(Utils.hashTwice(serialized));
        }
        return this.hash;
    }

    addInput(input: TransactionInput): void {
        this.inputs.push(input);
    }

    addOutput(output: TransactionOutput): void {
        this.outputs.push(output);
    }

    bitcoinSerialize(): Buffer {
        const buffer = Buffer.alloc(this.getMessageSize());
        const stream = new BitcoinSerializer(buffer);
        this.bitcoinSerializeToStream(stream);
        return buffer;
    }

    public getMessageSize(): number {
        let size = 8; // version + locktime
        size += VarInt.sizeOf(this.inputs.length);
        for (const input of this.inputs) {
            size += input.getMessageSize();
        }
        size += VarInt.sizeOf(this.outputs.length);
        for (const output of this.outputs) {
            size += output.getMessageSize();
        }
        return size;
    }

    public bitcoinSerializeToStream(stream: BitcoinSerializer): void {
        stream.writeUint32(this.version);
        stream.writeVarInt(this.inputs.length);
        for (const input of this.inputs) {
            input.bitcoinSerializeToStream(stream);
        }
        stream.writeVarInt(this.outputs.length);
        for (const output of this.outputs) {
            output.bitcoinSerializeToStream(stream);
        }
        stream.writeUint32(this.lockTime);
    }
}
