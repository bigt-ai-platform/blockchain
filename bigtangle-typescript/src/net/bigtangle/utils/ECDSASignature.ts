import bigInt, { BigInteger } from 'big-integer';
import { Utils } from './Utils';

// Curve parameters for secp256k1
const CURVE_ORDER = bigInt('FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141', 16);
const HALF_CURVE_ORDER = CURVE_ORDER.shiftRight(1);

export class ECDSASignature {
    constructor(
        public r: BigInteger, 
        public s: BigInteger, 
        public recoveryParam?: number
    ) {}

    toCanonicalised(): ECDSASignature {
        // Canonicalize the signature by ensuring the S value is not greater than half the curve order
        if (this.s.greater(HALF_CURVE_ORDER)) {
            return new ECDSASignature(
                this.r, 
                CURVE_ORDER.subtract(this.s),
                this.recoveryParam
            );
        }
        return this;
    }

    // New method to convert signature to DER-encoded base64 string
    toDerBase64(): string {
        return Utils.bytesToBase64(this.encodeToDER());
    }
    
    toDER(): Uint8Array {
        return this.encodeToDER();
    }

    // New static method to create from DER-encoded base64 string
    static fromDer(base64: string): ECDSASignature {
        const bytes = Utils.base64ToBytes(base64);
        return this.decodeFromDER(bytes);
    }

    encodeToDER(): Uint8Array {
        // Convert BigIntegers to byte arrays
        let rBytes = this.r.toArray(256).value;
        let sBytes = this.s.toArray(256).value;
        
        // Ensure positive integers (DER requires positive)
        if (rBytes[0] & 0x80) {
            rBytes = [0, ...rBytes];
        }
        if (sBytes[0] & 0x80) {
            sBytes = [0, ...sBytes];
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

    static decodeFromDER(bytes: Uint8Array): ECDSASignature {
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

    equals(other: ECDSASignature): boolean {
        return this.r.eq(other.r) && this.s.eq(other.s);
    }
}
