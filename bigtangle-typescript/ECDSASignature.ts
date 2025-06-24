import bigInt, { BigInteger } from 'big-integer';
import { Utils } from './Utils';

// Curve parameters for secp256k1
const CURVE_ORDER = bigInt('FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141', 16);
const HALF_CURVE_ORDER = CURVE_ORDER.shiftRight(1);

export class ECDSASignature {
    public readonly r: BigInteger;
    public readonly s: BigInteger;

    constructor(r: BigInteger, s: BigInteger) {
        this.r = r;
        this.s = s;
    }

    public isCanonical(): boolean {
        return this.s.compare(HALF_CURVE_ORDER) <= 0;
    }

    public toCanonicalised(): ECDSASignature {
        if (!this.isCanonical()) {
            return new ECDSASignature(this.r, CURVE_ORDER.subtract(this.s));
        }
        return this;
    }

    public encodeToDER(): Uint8Array {
        // Convert BigIntegers to byte arrays
        const rBytes = this.r.toArray(256).value;
        const sBytes = this.s.toArray(256).value;
        
        // Ensure positive integers (DER requires positive)
        if (rBytes[0] & 0x80) {
            rBytes.unshift(0);
        }
        if (sBytes[0] & 0x80) {
            sBytes.unshift(0);
        }
        
        const totalLength = 2 + rBytes.length + 2 + sBytes.length;
        const result = new Uint8Array(2 + totalLength);
        
        let offset = 0;
        result[offset++] = 0x30; // SEQUENCE
        result[offset++] = totalLength;
        
        // R value
        result[offset++] = 0x02; // INTEGER
        result[offset++] = rBytes.length;
        result.set(rBytes, offset);
        offset += rBytes.length;
        
        // S value
        result[offset++] = 0x02; // INTEGER
        result[offset++] = sBytes.length;
        result.set(sBytes, offset);
        
        return result;
    }

    public static decodeFromDER(bytes: Uint8Array): ECDSASignature {
        if (bytes[0] !== 0x30) {
            throw new Error("Invalid DER signature format");
        }
        
        const length = bytes[1];
        let offset = 2;
        
        // Read R value
        if (bytes[offset++] !== 0x02) {
            throw new Error("Invalid R value in DER signature");
        }
        const rLength = bytes[offset++];
        const rBytes = Array.from(bytes.slice(offset, offset + rLength));
        offset += rLength;
        const r = bigInt.fromArray(rBytes, 256);
        
        // Read S value
        if (bytes[offset++] !== 0x02) {
            throw new Error("Invalid S value in DER signature");
        }
        const sLength = bytes[offset++];
        const sBytes = Array.from(bytes.slice(offset, offset + sLength));
        const s = bigInt.fromArray(sBytes, 256);
        
        return new ECDSASignature(r, s);
    }

    public equals(other: ECDSASignature): boolean {
        return this.r.eq(other.r) && this.s.eq(other.s);
    }
}
