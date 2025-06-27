/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

import { sha256 } from '@noble/hashes/sha256';
import { sha512 } from '@noble/hashes/sha512';

// Mimic SpongyCastle's Digest interface
export interface Digest {
    update(data: Uint8Array): void;
    doFinal(): Uint8Array;
    reset(): void;
    getDigestSize(): number;
}

// A simple SHA256 Digest implementation using @noble/hashes
export class SHA256Digest implements Digest {
    private hash: ReturnType<typeof sha256>;
    private _digestSize: number;

    constructor() {
        this.hash = sha256.create();
        this._digestSize = 32; // SHA256 produces a 32-byte hash
    }

    update(data: Uint8Array): void {
        this.hash.update(data);
    }

    doFinal(): Uint8Array {
        const result = this.hash.digest();
        this.reset(); // Reset the hash after finalization
        return result;
    }

    reset(): void {
        this.hash = sha256.create();
    }

    getDigestSize(): number {
        return this._digestSize;
    }
}

// A simple SHA512 Digest implementation using @noble/hashes
export class SHA512Digest implements Digest {
    private hash: ReturnType<typeof sha512>;
    private _digestSize: number;

    constructor() {
        this.hash = sha512.create();
        this._digestSize = 64; // SHA512 produces a 64-byte hash
    }

    update(data: Uint8Array): void {
        this.hash.update(data);
    }

    doFinal(): Uint8Array {
        const result = this.hash.digest();
        this.reset(); // Reset the hash after finalization
        return result;
    }

    reset(): void {
        this.hash = sha512.create();
    }

    getDigestSize(): number {
        return this._digestSize;
    }
}


// Mimic SpongyCastle's DerivationParameters interface
export interface DerivationParameters {}

export class KDFParameters implements DerivationParameters {
    private sharedSecret: Uint8Array;
    private iv: Uint8Array | null;

    constructor(sharedSecret: Uint8Array, iv: Uint8Array | null) {
        this.sharedSecret = sharedSecret;
        this.iv = iv;
    }

    getSharedSecret(): Uint8Array {
        return this.sharedSecret;
    }

    getIV(): Uint8Array | null {
        return this.iv;
    }
}

export class ISO18033KDFParameters implements DerivationParameters {
    private seed: Uint8Array;

    constructor(seed: Uint8Array) {
        this.seed = seed;
    }

    getSeed(): Uint8Array {
        return this.seed;
    }
}

export interface DigestDerivationFunction {
    init(param: DerivationParameters): void;
    getDigest(): Digest;
    generateBytes(out: Uint8Array, outOff: number, len: number): number;
}

export class ConcatKDFBytesGenerator implements DigestDerivationFunction {
    private counterStart: number;
    private digest: Digest;
    private shared: Uint8Array | null = null;
    private iv: Uint8Array | null = null;

    protected constructor(counterStart: number, digest: Digest) {
        this.counterStart = counterStart;
        this.digest = digest;
    }

    public static create(digest: Digest): ConcatKDFBytesGenerator {
        return new ConcatKDFBytesGenerator(1, digest);
    }

    public init(param: DerivationParameters): void {
        if (param instanceof KDFParameters) {
            const p = param as KDFParameters;
            this.shared = p.getSharedSecret();
            this.iv = p.getIV();
        } else if (param instanceof ISO18033KDFParameters) {
            const p = param as ISO18033KDFParameters;
            this.shared = p.getSeed();
            this.iv = null;
        } else {
            throw new Error("KDF parameters required for ConcatKDFBytesGenerator");
        }
    }

    public getDigest(): Digest {
        return this.digest;
    }

    public generateBytes(out: Uint8Array, outOff: number, len: number): number {
        if (this.shared === null) {
            throw new Error("Shared secret not initialized. Call init() first with valid parameters.");
        }
        if ((out.length - len) < outOff) {
            throw new Error("output buffer too small");
        }

        const oBytes = len;
        const outLen = this.digest.getDigestSize();

        if (oBytes > ((2 << 32) - 1)) { // This check might need adjustment for JS number limits
            throw new Error("Output length too large");
        }

        const cThreshold = Math.ceil(oBytes / outLen);

        const dig = new Uint8Array(this.digest.getDigestSize());

        const C = new Uint8Array(4);
        // Helper to convert int to big-endian byte array
        const intToBigEndian = (num: number, arr: Uint8Array, off: number) => {
            arr[off] = (num >>> 24) & 0xFF;
            arr[off + 1] = (num >>> 16) & 0xFF;
            arr[off + 2] = (num >>> 8) & 0xFF;
            arr[off + 3] = num & 0xFF;
        };

        intToBigEndian(this.counterStart, C, 0);

        let counterBase = this.counterStart & ~0xFF;

        for (let i = 0; i < cThreshold; i++) {
            this.digest.update(C);
            this.digest.update(this.shared);

            if (this.iv !== null) {
                this.digest.update(this.iv);
            }

            const currentDig = this.digest.doFinal();
            dig.set(currentDig);

            if (len > outLen) {
                out.set(dig.slice(0, outLen), outOff);
                outOff += outLen;
                len -= outLen;
            } else {
                out.set(dig.slice(0, len), outOff);
            }

            // Increment C[3] and handle overflow
            C[3]++;
            if (C[3] === 0) { // Overflow
                counterBase += 0x100;
                intToBigEndian(counterBase, C, 0);
            }
        }

        this.digest.reset();

        return oBytes;
    }
}
