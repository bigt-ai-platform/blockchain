import { ECPoint } from '../core/ECPoint';
import { secp256k1 } from '@noble/curves/secp256k1';
import { BigInteger } from '../core/BigInteger';

/**
 * A wrapper around ECPoint that delays decoding of the point for as long as possible. This is useful because point
 * encode/decode in Bouncy Castle is quite slow especially on Dalvik, as it often involves decompression/recompression.
 */
export class LazyECPoint {
    private readonly curve: typeof secp256k1 | null;
    private readonly bits: Uint8Array | null;

    private point: ECPoint | null = null;

    constructor(curve: typeof secp256k1, bits: Uint8Array);
    constructor(point: ECPoint);
    constructor(arg1: typeof secp256k1 | ECPoint, arg2?: Uint8Array) {
        if (arg1 instanceof ECPoint) {
            this.point = arg1;
            this.curve = null;
            this.bits = null;
        } else {
            this.curve = arg1;
            this.bits = arg2 as Uint8Array;
        }
    }

    public get(): ECPoint {
        if (this.point === null) {
            if (this.curve === null || this.bits === null) {
                throw new Error("LazyECPoint not initialized with curve and bits");
            }
            this.point = ECPoint.decodePoint(this.bits);
        }
        return this.point;
    }

    // Delegated methods.

    public getDetachedPoint(): ECPoint {
        return this.get().getDetachedPoint();
    }

   
    public isInfinity(): boolean {
        return this.get().isInfinity();
    }

    public timesPow2(e: number): ECPoint {
        // Ensure exponentiation uses BigInteger for compatibility with ECPoint.multiply
        // BigInteger.fromString handles string conversion from bigint
        const exp = BigInt(2) ** BigInt(e);
        // If ECPoint.multiply expects BigInteger, convert
        // @ts-ignore: BigInteger may be required by multiply
        return this.get().multiply(typeof BigInteger !== 'undefined' ? BigInteger.fromString(exp.toString()) : exp);
    }

    // Assuming ECFieldElement and related methods are handled within ECPoint or a separate utility
    // public getYCoord(): any { return this.get().getYCoord(); }
    // public getZCoords(): any[] { return this.get().getZCoords(); }

    public isNormalized(): boolean {
        return this.get().isNormalized();
    }

    public isCompressed(): boolean {
        if (this.bits !== null) {
            return this.bits[0] === 2 || this.bits[0] === 3;
        } else {
            return this.get().isCompressed();
        }
    }

    public multiply(k: bigint): ECPoint {
        // Convert bigint to BigInteger before passing to multiply if required
        // @ts-ignore: BigInteger may be required by multiply
        return this.get().multiply(typeof BigInteger !== 'undefined' ? BigInteger.fromString(k.toString()) : k);
    }

    public subtract(b: ECPoint): ECPoint {
        return this.get().add(b.negate());
    }

    public isValid(): boolean {
        return this.get().isValid();
    }

    // public scaleY(scale: any): ECPoint { return this.get().scaleY(scale); }

    // public getXCoord(): any { return this.get().getXCoord(); }

    // public scaleX(scale: any): ECPoint { return this.get().scaleX(scale); }

    public equals(other: ECPoint): boolean {
        return this.get().equals(other);
    }

    public negate(): ECPoint {
        return this.get().negate();
    }

    public threeTimes(): ECPoint {
        return this.get().threeTimes();
    }

    // public getZCoord(index: number): any { return this.get().getZCoord(index); }

    public getEncoded(compressed: boolean): Uint8Array {
        if (compressed === this.isCompressed() && this.bits !== null) {
            return new Uint8Array(this.bits);
        } else {
            return this.get().encode(compressed);
        }
    }

    public add(b: ECPoint): ECPoint {
        return this.get().add(b);
    }

    public twicePlus(b: ECPoint): ECPoint {
        return this.get().twicePlus(b);
    }

    public getCurve(): typeof secp256k1 {
        return secp256k1;
    }

    public normalize(): ECPoint {
        return this.get().normalize();
    }

    // public getY(): any { return this.normalize().getYCoord(); }

    public twice(): ECPoint {
        return this.get().twice();
    }

    // public getAffineYCoord(): any { return this.get().getAffineYCoord(); }

    // public getAffineXCoord(): any { return this.get().getAffineXCoord(); }

    // public getX(): any { return this.normalize().getXCoord(); }

    public equalsOther(o: any): boolean {
        if (this === o) return true;
        if (o == null || !(o instanceof LazyECPoint)) return false;
        const a = this.getCanonicalEncoding();
        const b = o.getCanonicalEncoding();
        return LazyECPoint.bytesEqual(a, b);
    }

    public hashCode(): number {
        let hash = 0;
        const encoding = this.getCanonicalEncoding();
        for (const byte of encoding) {
            hash = (hash << 5) - hash + byte;
            hash |= 0; // Convert to 32bit integer
        }
        return hash;
    }

    private getCanonicalEncoding(): Uint8Array {
        return this.getEncoded(true);
    }

    // Helper: compare two Uint8Arrays
    private static bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
        if (a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) {
            if (a[i] !== b[i]) return false;
        }
        return true;
    }
}