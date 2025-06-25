export class DataOutputStream {
    private buffer: number[];

    constructor() {
        this.buffer = [];
    }

    writeBoolean(value: boolean): void {
        this.buffer.push(value ? 1 : 0);
    }

    writeInt(value: number): void {
        // Write as big-endian
        this.buffer.push((value >> 24) & 0xFF);
        this.buffer.push((value >> 16) & 0xFF);
        this.buffer.push((value >> 8) & 0xFF);
        this.buffer.push(value & 0xFF);
    }

    write(buf: Uint8Array): void {
        for (let i = 0; i < buf.length; i++) {
            this.buffer.push(buf[i]);
        }
    }

    writeByte(value: number): void {
        this.buffer.push(value & 0xFF);
    }

    toByteArray(): Uint8Array {
        return new Uint8Array(this.buffer);
    }
}
