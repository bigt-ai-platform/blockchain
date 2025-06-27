import { ChildNumber } from './ChildNumber';
import { hmac } from '@noble/hashes/hmac';
import { sha512 } from '@noble/hashes/sha512';

export class HDUtils {
    static formatPath(path: ChildNumber[]): string {
        return path.map(p => p.toString()).join('/');
    }

    static append(path: ChildNumber[], childNumber: ChildNumber): ChildNumber[] {
        return [...path, childNumber];
    }

    static hmacSha512(key: string | Uint8Array, data: Uint8Array): Uint8Array {
        const keyBytes = typeof key === 'string' ? new TextEncoder().encode(key) : key;
        return hmac(sha512, keyBytes, data);
    }

    static hmacSha512Bytes(chainCode: Uint8Array, data: Uint8Array): Uint8Array {
        return hmac(sha512, chainCode, data);
    }
}