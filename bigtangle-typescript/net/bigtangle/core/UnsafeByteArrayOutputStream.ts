import { Buffer } from 'buffer';

export class UnsafeByteArrayOutputStream {
    private buffers: Buffer[] = [];
    private size: number = 0;

    write(chunk: Buffer): void {
        this.buffers.push(chunk);
        this.size += chunk.length;
    }

    toBuffer(): Buffer {
        return Buffer.concat(this.buffers, this.size);
    }
}
