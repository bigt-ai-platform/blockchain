import { Utils } from './Utils';
import * as crypto from 'crypto';

export class Sha256Hash {
    private bytes: Uint8Array;

    constructor(bytes: Uint8Array) {
        if (bytes.length !== 32) {
            throw new Error("Sha256Hash must be 32 bytes long.");
        }
        this.bytes = bytes;
    }

    static wrap(bytes: Uint8Array): Sha256Hash {
        return new Sha256Hash(bytes);
    }

    static wrapReversed(bytes: Uint8Array): Sha256Hash {
        return new Sha256Hash(Utils.reverseBytes(bytes));
    }

    static of(data: Uint8Array): Sha256Hash {
        return new Sha256Hash(Sha256Hash.hash(data));
    }

    static hash(input: Uint8Array): Uint8Array {
        const hash = crypto.createHash('sha256');
        hash.update(Buffer.from(input));
        return new Uint8Array(hash.digest());
    }

    static hashTwice(input: Uint8Array): Uint8Array {
        return Sha256Hash.hash(Sha256Hash.hash(input));
    }

    static twiceOf(data: Uint8Array): Sha256Hash {
        return new Sha256Hash(Sha256Hash.hashTwice(data));
    }

    getReversedBytes(): Uint8Array {
        return Utils.reverseBytes(this.bytes);
    }

    getBytes(): Uint8Array {
        return this.bytes;
    }

    toBigInteger(): bigInt.BigInteger {
        return Utils.bytesToBigInteger(this.bytes);
    }

    toString(): string {
        return Utils.HEX.encode(this.bytes);
    }

    getHashAsString(): string {
        return this.toString();
    }

    equals(other: any): boolean {
        if (!(other instanceof Sha256Hash)) {
            return false;
        }
        return Utils.arraysEqual(this.bytes, other.bytes);
    }

    // Placeholder for ZERO_HASH
    static ZERO_HASH = new Sha256Hash(new Uint8Array(32));
}
