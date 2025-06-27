import { Buffer } from 'buffer';

export class DataOutputStream {
    private buffer: Buffer;
    private position: number;

    constructor() {
        this.buffer = Buffer.alloc(0);
        this.position = 0;
    }

    public write(buffer: Buffer): void {
        this.buffer = Buffer.concat([this.buffer, buffer]);
        this.position += buffer.length;
    }

    public writeBoolean(val: boolean): void {
        const buf = Buffer.alloc(1);
        buf.writeUInt8(val ? 1 : 0, 0);
        this.write(buf);
    }

    public writeByte(val: number): void {
        const buf = Buffer.alloc(1);
        buf.writeUInt8(val, 0);
        this.write(buf);
    }

    public writeInt(val: number): void {
        const buf = Buffer.alloc(4);
        buf.writeInt32BE(val, 0);
        this.write(buf);
    }

    public writeLong(val: number): void {
        const buf = Buffer.alloc(8);
        buf.writeBigInt64BE(BigInt(val), 0);
        this.write(buf);
    }

    public writeBytes(buffer: Buffer): void {
        this.write(buffer);
    }

    public writeNBytesString(val: string | null): void {
        this.writeBoolean(val !== null);
        if (val !== null) {
            const buf = Buffer.from(val, 'utf-8');
            this.writeInt(buf.length);
            this.write(buf);
        }
    }

    public toByteArray(): Buffer {
        return this.buffer;
    }

    public close(): void {
        // No-op
    }
}