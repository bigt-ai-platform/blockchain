import { BigInteger } from '../core/BigInteger';
import { ECDSASignature } from './ECDSASignature';
import { SigHash } from '../core/Transaction';
import { VerificationException } from '../exception/VerificationException';
import { Utils } from '../utils/Utils';
import { ECKey } from '../core/ECKey';
import * as secp256k1 from '@noble/secp256k1';

/**
 * A TransactionSignature wraps an {@link net.bigtangle.core.ECKey.ECDSASignature} and adds methods for handling
 * the additional SIGHASH mode byte that is used.
 */
export class TransactionSignature extends ECDSASignature {
    /**
     * A byte that controls which parts of a transaction are signed. This is exposed because signatures
     * parsed off the wire may have sighash flags that aren't "normal" serializations of the enum values.
     * Because Bitcoin Core works via bit testing, we must not lose the exact value when round-tripping
     * otherwise we'll fail to verify signature hashes.
     */
    public readonly sighashFlags: number;

    /** Constructs a signature with the given components and SIGHASH_ALL. */
    constructor(r: BigInteger, s: BigInteger);
    /** Constructs a signature with the given components and raw sighash flag bytes (needed for rule compatibility). */
    constructor(r: BigInteger, s: BigInteger, sighashFlags: number);
    /** Constructs a transaction signature based on the ECDSA signature. */
    constructor(signature: ECDSASignature, mode: SigHash, anyoneCanPay: boolean);
    constructor(...args: any[]) {
        if (args.length === 2) {
            super(args[0], args[1]);
            this.sighashFlags = SigHash.ALL;
        } else if (args.length === 3) {
            if (typeof args[2] === 'number') {
                super(args[0], args[1]);
                this.sighashFlags = args[2];
            } else {
                super(args[0].r, args[0].s);
                this.sighashFlags = TransactionSignature.calcSigHashValue(args[1], args[2]);
            }
        } else {
            throw new Error("Invalid constructor arguments");
        }
    }

    /**
     * Returns a dummy invalid signature whose R/S values are set such that they will take up the same number of
     * encoded bytes as a real signature. This can be useful when you want to fill out a transaction to be of the
     * right size (e.g. for fee calculations) but don't have the requisite signing key yet and will fill out the
     * real signature later.
     */
    public static dummy(): TransactionSignature {
        // In the original Java code, HALF_CURVE_ORDER is used. We need to find an equivalent in noble-curves.
        // secp256k1.CURVE.n is the order of the curve.
        const halfCurveOrder = new BigInteger(secp256k1.CURVE.n.toString()).divide(new BigInteger("2"));
        return new TransactionSignature(halfCurveOrder, halfCurveOrder);
    }

    /** Calculates the byte used in the protocol to represent the combination of mode and anyoneCanPay. */
    public static calcSigHashValue(mode: SigHash, anyoneCanPay: boolean): number {
        if (!(mode === SigHash.ALL || mode === SigHash.NONE || mode === SigHash.SINGLE)) {
            throw new Error("Unsupported SigHash mode");
        }
        let sighashFlags = mode;
        if (anyoneCanPay) {
            sighashFlags |= SigHash.ANYONECANPAY;
        }
        return sighashFlags;
    }

    /**
     * Returns true if the given signature is has canonical encoding, and will thus be accepted as standard by
     * Bitcoin Core. DER and the SIGHASH encoding allow for quite some flexibility in how the same structures
     * are encoded, and this can open up novel attacks in which a man in the middle takes a transaction and then
     * changes its signature such that the transaction hash is different but it's still valid. This can confuse wallets
     * and generally violates people's mental model of how Bitcoin should work, thus, non-canonical signatures are now
     * not relayed by default.
     */
    public static isEncodingCanonical(signature: Uint8Array): boolean {
        // See Bitcoin Core's IsCanonicalSignature, https://bitcointalk.org/index.php?topic=8392.msg127623#msg127623
        // A canonical signature exists of: <30> <total len> <02> <len R> <R> <02> <len S> <S> <hashtype>
        // Where R and S are not negative (their first byte has its highest bit not set), and not
        // excessively padded (do not start with a 0 byte, unless an otherwise negative number follows,
        // in which case a single 0 byte is necessary and even required).
        if (signature.length < 9 || signature.length > 73) {
            return false;
        }

        const hashType = (signature[signature.length - 1] & 0xff) & ~SigHash.ANYONECANPAY; // mask the byte to prevent sign-extension hurting us
        if (hashType < SigHash.ALL || hashType > SigHash.SINGLE) {
            return false;
        }

        //                   "wrong type"                  "wrong length marker"
        if ((signature[0] & 0xff) !== 0x30 || (signature[1] & 0xff) !== signature.length - 3) {
            return false;
        }

        const lenR = signature[3] & 0xff;
        if (5 + lenR >= signature.length || lenR === 0) {
            return false;
        }
        const lenS = signature[5 + lenR] & 0xff;
        if (lenR + lenS + 7 !== signature.length || lenS === 0) {
            return false;
        }

        //    R value type mismatch          R value negative
        if (signature[4 - 2] !== 0x02 || (signature[4] & 0x80) === 0x80) {
            return false;
        }
        if (lenR > 1 && signature[4] === 0x00 && (signature[4 + 1] & 0x80) !== 0x80) {
            return false; // R value excessively padded
        }

        //       S value type mismatch                    S value negative
        if (signature[6 + lenR - 2] !== 0x02 || (signature[6 + lenR] & 0x80) === 0x80) {
            return false;
        }
        if (lenS > 1 && signature[6 + lenR] === 0x00 && (signature[6 + lenR + 1] & 0x80) !== 0x80) {
            return false; // S value excessively padded
        }

        return true;
    }

    public anyoneCanPay(): boolean {
        return (this.sighashFlags & SigHash.ANYONECANPAY) !== 0;
    }

    public sigHashMode(): SigHash {
        const mode = this.sighashFlags & 0x1f;
        if (mode === SigHash.NONE) {
            return SigHash.NONE;
        } else if (mode === SigHash.SINGLE) {
            return SigHash.SINGLE;
        } else {
            return SigHash.ALL;
        }
    }

    /**
     * What we get back from the signer are the two components of a signature, r and s. To get a flat byte stream
     * of the type used by Bitcoin we have to encode them using DER encoding, which is just a way to pack the two
     * components into a structure, and then we append a byte to the end for the sighash flags.
     */
    public encodeToBitcoin(): Uint8Array {
        const derBytes = this.derByteStream();
        const output = new Uint8Array(derBytes.length + 1);
        output.set(derBytes, 0);
        output[derBytes.length] = this.sighashFlags;
        return output;
    }

    public toCanonicalised(): TransactionSignature {
        const canonicalisedECDSASig = super.toCanonicalised();
        return new TransactionSignature(canonicalisedECDSASig, this.sigHashMode(), this.anyoneCanPay());
    }

    /**
     * Returns a decoded signature.
     *
     * @param bytes The signature bytes.
     * @param requireCanonicalEncoding if the encoding of the signature must
     * be canonical.
     * @param requireCanonicalSValue if the S-value must be canonical (below half
     * the order of the curve).
     * @throws VerificationException if the signature is invalid or unparseable in some way.
     */
    public static decodeFromBitcoin(bytes: Uint8Array,
                                     requireCanonicalEncoding: boolean,
                                     requireCanonicalSValue: boolean): TransactionSignature {
        // Bitcoin encoding is DER signature + sighash byte.
        if (requireCanonicalEncoding && !TransactionSignature.isEncodingCanonical(bytes)) {
            throw new VerificationException("Signature encoding is not canonical.");
        }
        
        const sigHashByte = bytes[bytes.length - 1];
        const derBytes = bytes.slice(0, bytes.length - 1);

        let sig: ECDSASignature;
        try {
            sig = ECDSASignature.decodeFromDER(derBytes);
        } catch (e: any) {
            throw new VerificationException("Could not decode DER: " + e.message, e);
        }
        if (requireCanonicalSValue && !sig.isCanonical()) {
            throw new VerificationException("S-value is not canonical.");
        }

        // In Bitcoin, any value of the final byte is valid, but not necessarily canonical. See javadocs for
        // isEncodingCanonical to learn more about this. So we must store the exact byte found.
        return new TransactionSignature(sig.r, sig.s, sigHashByte);
    }
}
