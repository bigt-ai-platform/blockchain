import { DerivationFunction, DerivationParameters } from './IESEngine';
import { Digest } from './ConcatKDFBytesGenerator';

export class MGFParameters implements DerivationParameters {
    constructor(public seed: Uint8Array) {}
}

/**
 * This class is borrowed from spongycastle project
 * The only change made is addition of 'counterStart' parameter to
 * conform to Crypto++ capabilities
 */
export class MGF1BytesGeneratorExt implements DerivationFunction {
    private digest: Digest;
    private seed: Uint8Array | null = null;
    private hLen: number;
    private counterStart: number;

    constructor(digest: Digest, counterStart: number) {
        this.digest = digest;
        this.hLen = digest.getDigestSize();
        this.counterStart = counterStart;
    }

    public init(param: DerivationParameters): void {
        if (!(param instanceof MGFParameters)) {
            throw new Error("MGF parameters required for MGF1Generator");
        } else {
            const p = param as MGFParameters;
            this.seed = p.seed;
        }
    }

    public getDigest(): Digest {
        return this.digest;
    }

    private ItoOSP(i: number, sp: Uint8Array): void {
        sp[0] = (i >>> 24) & 0xFF;
        sp[1] = (i >>> 16) & 0xFF;
        sp[2] = (i >>> 8) & 0xFF;
        sp[3] = i & 0xFF;
    }

    public generateBytes(out: Uint8Array, outOff: number, len: number): number {
        if (out.length - len < outOff) {
            throw new Error("output buffer too small");
        }
        if (this.seed === null) {
            throw new Error("MGF1BytesGeneratorExt not initialized with seed");
        }

        const hashBuf = new Uint8Array(this.hLen);
        const C = new Uint8Array(4);
        let counter = 0;
        let hashCounter = this.counterStart;
        this.digest.reset();

        if (len > this.hLen) {
            do {
                this.ItoOSP(hashCounter++, C);
                this.digest.update(this.seed);
                this.digest.update(C);
                const result = this.digest.doFinal();
                hashBuf.set(result);
                out.set(hashBuf, outOff + counter * this.hLen);
                counter++;
            } while (counter < Math.floor(len / this.hLen));
        }

        if (counter * this.hLen < len) {
            this.ItoOSP(hashCounter, C);
            this.digest.update(this.seed);
            this.digest.update(C);
            const result = this.digest.doFinal();
            hashBuf.set(result);
            out.set(hashBuf.slice(0, len - counter * this.hLen), outOff + counter * this.hLen);
        }

        return len;
    }
}
