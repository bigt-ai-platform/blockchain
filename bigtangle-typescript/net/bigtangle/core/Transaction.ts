import { NetworkParameters } from './NetworkParameters';
import { VarInt } from './VarInt';
import { MessageSerializer } from './MessageSerializer';
import { Buffer } from 'buffer';
import { TransactionInput } from './TransactionInput';
import { TransactionOutput } from './TransactionOutput';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class Transaction {
    private params: NetworkParameters;
    private version: number = 0;
    private inputs: TransactionInput[] = [];
    private outputs: TransactionOutput[] = [];

    constructor(params: NetworkParameters, bytes?: Buffer, offset?: number, serializer?: MessageSerializer) {
        this.params = params;
        
        if (bytes && offset !== undefined) {
            this.parse(bytes, offset, serializer);
        }
    }

    private parse(bytes: Buffer, offset: number, serializer?: MessageSerializer): void {
        this.version = bytes.readUInt32LE(offset);
        offset += 4;

        // Parse inputs
        const inCountResult = VarInt.read(bytes, offset);
        offset += inCountResult.size;
        
        for (let i = 0; i < inCountResult.value; i++) {
            const input = new TransactionInput(this.params, bytes, offset);
            this.inputs.push(input);
            offset += input.getMessageSize();
        }

        // Parse outputs
        const outCountResult = VarInt.read(bytes, offset);
        offset += outCountResult.size;
        
        for (let i = 0; i < outCountResult.value; i++) {
            const output = new TransactionOutput(this.params, bytes, offset);
            this.outputs.push(output);
            offset += output.getMessageSize();
        }
    }

    public bitcoinSerialize(): Buffer {
        const out = new UnsafeByteArrayOutputStream();
        
        // Serialize version
        const versionBuf = Buffer.alloc(4);
        versionBuf.writeUInt32LE(this.version, 0);
        out.write(versionBuf);

        // Serialize inputs
        VarInt.write(this.inputs.length, out);
        for (const input of this.inputs) {
            out.write(input.bitcoinSerialize());
        }

        // Serialize outputs
        VarInt.write(this.outputs.length, out);
        for (const output of this.outputs) {
            out.write(output.bitcoinSerialize());
        }

        return out.toBuffer();
    }

    public getMessageSize(): number {
        let size = 4; // version
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

    // Getters and setters
    public getVersion(): number { return this.version; }
    public setVersion(version: number): void { this.version = version; }
    
    public getInputs(): TransactionInput[] { return this.inputs; }
    public setInputs(inputs: TransactionInput[]): void { this.inputs = inputs; }
    
    public getOutputs(): TransactionOutput[] { return this.outputs; }
    public setOutputs(outputs: TransactionOutput[]): void { this.outputs = outputs; }
}
