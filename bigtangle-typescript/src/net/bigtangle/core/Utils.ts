import { Buffer } from 'buffer';
import { createHash } from 'crypto';
import base58 from 'bs58';

export class Utils {
    public static UTF8 = {
        encode: (str: string): Buffer => Buffer.from(str, 'utf8'),
        decode: (buf: Buffer): string => buf.toString('utf8')
    };

    public static base58ToBytes(base58String: string): Buffer {
        return Buffer.from(base58.decode(base58String));
    }

    public static bytesToBase58(bytes: Buffer): string {
        return base58.encode(bytes);
    }

    public static toHexString(bytes: Buffer): string {
        return bytes.toString('hex');
    }

    public static reverseBytes(bytes: Buffer): Buffer {
        return Buffer.from(bytes).reverse();
    }

    public static doubleDigest(buffer: Buffer | Uint8Array): Buffer {
        const inputBuffer = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer);
        const first = createHash('sha256').update(inputBuffer).digest();
        return createHash('sha256').update(first).digest();
    }

    public static arraysEqual(a: Buffer, b: Buffer): boolean {
        if (a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) {
            if (a[i] !== b[i]) return false;
        }
        return true;
    }

    public static writeNBytesString(out: Buffer, str: string): void {
        const bytes = Buffer.from(str, 'utf8');
        out.writeUInt32BE(bytes.length);
        out.write(str, out.length, bytes.length, 'utf8');
    }

    public static readNBytesString(input: Buffer): string {
        const length = input.readUInt32BE();
        return input.toString('utf8', 4, 4 + length);
    }

    public static concatArrays(...arrays: Uint8Array[]): Uint8Array {
        const totalLength = arrays.reduce((acc, arr) => acc + arr.length, 0);
        const result = new Uint8Array(totalLength);
        let offset = 0;
        for (const arr of arrays) {
            result.set(arr, offset);
            offset += arr.length;
        }
        return result;
    }
}
