import { Buffer } from 'buffer';

export class DataInputStream {
    private buffer: Buffer;
    private position: number;

    constructor(buffer: Buffer) {
        this.buffer = buffer;
        this.position = 0;
    }

    public read(buffer: Buffer, offset: number, length: number): number {
        const bytesRead = this.buffer.copy(buffer, offset, this.position, this.position + length);
        this.position += bytesRead;
        return bytesRead;
    }

    public readBoolean(): boolean {
        return this.readByte() !== 0;
    }

    public readByte(): number {
        const val = this.buffer.readUInt8(this.position);
        this.position += 1;
        return val;
    }

    public readInt(): number {
        const val = this.buffer.readInt32BE(this.position);
        this.position += 4;
        return val;
    }

    public readLong(): number {
        const val = this.buffer.readBigInt64BE(this.position);
        this.position += 8;
        return Number(val);
    }

    public readBytes(length: number): Buffer {
        const buf = this.buffer.slice(this.position, this.position + length);
        this.position += length;
        return buf;
    }

    public readNBytesString(): string | null {
        const hasValue = this.readBoolean();
        if (hasValue) {
            const length = this.readInt();
            const buf = this.readBytes(length);
            return buf.toString('utf-8');
        } else {
            return null;
        }
    }

    public close(): void {
        // No-op
    }
}