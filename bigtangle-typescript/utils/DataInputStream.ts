export class DataInputStream {
    private buffer: Uint8Array;
    private offset: number;

    constructor(buffer: Uint8Array) {
        this.buffer = buffer;
        this.offset = 0;
    }

    readBoolean(): boolean {
        if (this.offset + 1 > this.buffer.length) {
            throw new Error("EOF");
        }
        return this.buffer[this.offset++] !== 0;
    }

    readInt(): number {
        if (this.offset + 4 > this.buffer.length) {
            throw new Error("EOF");
        }
        const value = new DataView(this.buffer.buffer, this.buffer.byteOffset + this.offset, 4).getInt32(0, false); // Big-endian
        this.offset += 4;
        return value;
    }

    readFully(buf: Uint8Array): void {
        if (this.offset + buf.length > this.buffer.length) {
            throw new Error("EOF");
        }
        for (let i = 0; i < buf.length; i++) {
            buf[i] = this.buffer[this.offset++];
        }
    }

    readByte(): number {
        if (this.offset + 1 > this.buffer.length) {
            throw new Error("EOF");
        }
        return this.buffer[this.offset++];
    }
}
