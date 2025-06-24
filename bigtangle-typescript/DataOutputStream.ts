import { VarInt } from './VarInt';
import { Buffer } from 'buffer';

export class BitcoinOutputStream {
    private buffer: Buffer;
    private position: number;

    constructor(buffer: Buffer) {
        this.buffer = buffer;
        this.position = 0;
    }

    write(data: Uint8Array): void {
        this.buffer.set(data, this.position);
        this.position += data.length;
    }

    writeUint32(value: number): void {
        this.buffer.writeUInt32LE(value, this.position);
        this.position += 4;
    }

    writeVarInt(value: number): void {
        const varint = new VarInt(value);
        const encoded = varint.encode();
        this.write(encoded);
    }
}
