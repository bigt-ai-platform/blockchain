import { Buffer } from 'buffer';

export class ByteArrayInputStream {
    private buffer: Buffer;
    private position: number = 0;

    constructor(buffer: Buffer | Uint8Array) {
        this.buffer = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer);
    }

    public read(length: number): Buffer {
        const result = this.buffer.slice(this.position, this.position + length);
        this.position += length;
        return result;
    }

    public readByte(): number {
        const result = this.buffer.readUInt8(this.position);
        this.position += 1;
        return result;
    }

    public available(): number {
        return this.buffer.length - this.position;
    }

    public close(): void {
        // Nothing to do
    }
}
