import { Utils } from './Utils';
import * as crypto from 'crypto';
import { Buffer } from 'buffer';

export class Sha256Hash {
    private bytes: Buffer;

    constructor(bytes: Buffer) {
        if (bytes.length !== 32) {
            throw new Error("Sha256Hash must be 32 bytes long.");
        }
        this.bytes = bytes;
    }

    static wrap(bytes: Buffer): Sha256Hash {
        return new Sha256Hash(bytes);
    }

    static wrapReversed(bytes: Buffer): Sha256Hash {
        return new Sha256Hash(Buffer.from(bytes).reverse());
    }

    static of(data: Buffer): Sha256Hash {
        return new Sha256Hash(Sha256Hash.hash(data));
    }

    static hash(input: Buffer): Buffer {
        const hash = crypto.createHash('sha256');
        hash.update(input);
        return hash.digest();
    }

    static hashTwice(input: Buffer): Buffer {
        return Sha256Hash.hash(Sha256Hash.hash(input));
    }

    static twiceOf(data: Buffer): Sha256Hash {
        return new Sha256Hash(Sha256Hash.hashTwice(data));
    }

    getReversedBytes(): Buffer {
        return Buffer.from(this.bytes).reverse();
    }

    getBytes(): Buffer {
        return this.bytes;
    }

    toString(): string {
        return this.bytes.toString('hex');
    }

    getHashAsString(): string {
        return this.toString();
    }

    equals(other: any): boolean {
        if (!(other instanceof Sha256Hash)) {
            return false;
        }
        return this.bytes.equals(other.bytes);
    }

    // Placeholder for ZERO_HASH
    static ZERO_HASH = new Sha256Hash(Buffer.alloc(32));
}
