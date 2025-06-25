import { Buffer } from 'buffer';

export class VarInt {
    static read(buffer: Buffer, offset: number): { value: number; size: number } {
        const first = buffer.readUInt8(offset);
        offset++;
        
        if (first < 0xfd) {
            return { value: first, size: 1 };
        } else if (first === 0xfd) {
            return { value: buffer.readUInt16LE(offset), size: 3 };
        } else if (first === 0xfe) {
            return { value: buffer.readUInt32LE(offset), size: 5 };
        } else {
            // For 64-bit values, we need to handle bigint but we'll use number for now
            const low = buffer.readUInt32LE(offset);
            const high = buffer.readUInt32LE(offset + 4);
            const value = (high * 0x100000000) + low;
            return { value, size: 9 };
        }
    }

    static write(value: number, out: any): void {
        if (value < 0xfd) {
            out.write(Buffer.from([value]));
        } else if (value <= 0xffff) {
            const buf = Buffer.alloc(3);
            buf.writeUInt8(0xfd, 0);
            buf.writeUInt16LE(value, 1);
            out.write(buf);
        } else if (value <= 0xffffffff) {
            const buf = Buffer.alloc(5);
            buf.writeUInt8(0xfe, 0);
            buf.writeUInt32LE(value, 1);
            out.write(buf);
        } else {
            const buf = Buffer.alloc(9);
            buf.writeUInt8(0xff, 0);
            // Write as 64-bit little endian
            const low = value % 0x100000000;
            const high = Math.floor(value / 0x100000000);
            buf.writeUInt32LE(low, 1);
            buf.writeUInt32LE(high, 5);
            out.write(buf);
        }
    }

    static sizeOf(value: number): number {
        if (value < 0xfd) {
            return 1;
        } else if (value <= 0xffff) {
            return 3;
        } else if (value <= 0xffffffff) {
            return 5;
        } else {
            return 9;
        }
    }
}
