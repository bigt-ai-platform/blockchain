import { createHash } from 'crypto';
import { Buffer } from 'buffer';
import { Utils } from './Utils';

export class Sha256Hash {
        bytes: Buffer;

    private constructor(bytes: Buffer) {
        if (bytes.length !== 32) {
            throw new Error('Sha256Hash must be exactly 32 bytes');
        }
        this.bytes = bytes;
    }

    public static hash(data: Buffer): Sha256Hash {
        const hash = createHash('sha256');
        hash.update(data);
        return new Sha256Hash(Buffer.from(hash.digest()));
    }

    public static hashTwice(data: Buffer): Sha256Hash {
        const firstPass = createHash('sha256').update(data).digest();
        return Sha256Hash.hash(Buffer.from(firstPass));
    }

    public static wrap(hash: Buffer): Sha256Hash {
        return new Sha256Hash(hash);
    }
    public static of(contents: Buffer): Sha256Hash {
        return Sha256Hash.hash(contents);
    }

    public static wrapReversed(hash: Buffer): Sha256Hash {
        return new Sha256Hash(Buffer.from(Utils.reverseBytes(hash)));
    }

    public toBuffer(): Buffer {
        return this.bytes;
    }

    public toReversedBuffer(): Buffer {
        return Buffer.from(Utils.reverseBytes(this.bytes));
    }

    public toString(): string {
        return Utils.toHexString(this.bytes);
    }

    public equals(other: Sha256Hash): boolean {
        return this.bytes.equals(other.bytes);
    }

    public getBytes(): Buffer {
        return Buffer.from( this.bytes);
    }

        public getReversedBytes(): Buffer {
        return Buffer.from(Utils.reverseBytes(this.bytes));
    }


    public getHash(): Sha256Hash {
        return this;
    }

    public slice(start: number, end: number): Buffer {
        return this.bytes.slice(start, end);
    }

    public subarray(start: number, end: number): Buffer {
        return this.bytes.subarray(start, end);
    }

    public static ZERO_HASH = Sha256Hash.wrap(Buffer.alloc(32));
}
