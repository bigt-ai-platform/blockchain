/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2013 Matija Mazi.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { ChildNumber } from './ChildNumber';
import { DeterministicKey } from './DeterministicKey';
import { ECKey } from '../core/ECKey';
import { ECPoint } from '../core/ECPoint';
import { HDDerivationException } from './HDDerivationException';
import { HDUtils } from './HDUtils';
import { BigInteger } from '../core/BigInteger';
import { randomBytes } from 'crypto';

/**
 * Implementation of the <a href="https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki">BIP 32</a>
 * deterministic wallet child key generation algorithm.
 */
export class HDKeyDerivation {
    // Helper: Uint8Array to hex string
    private static bufferToHex(buf: Uint8Array): string {
        return Buffer.from(buf).toString('hex');
    }
    // Helper: BigInt to BigInteger
    private static bigIntToBigInteger(bi: bigint): BigInteger {
        return new BigInteger(bi.toString(16), 16);
    }
    // Helper: BigInteger to Uint8Array (32 bytes)
    private static bigIntegerToBytes(bi: BigInteger, length: number = 32): Uint8Array {
        let hex = bi.toString(16);
        if (hex.length % 2 !== 0) hex = '0' + hex;
        let bytes = Buffer.from(hex, 'hex');
        if (bytes.length < length) {
            const pad = Buffer.alloc(length - bytes.length, 0);
            bytes = Buffer.concat([pad, bytes]);
        }
        return new Uint8Array(bytes);
    }

    // Some arbitrary random number. Doesn't matter what it is.
    private static readonly RAND_INT: BigInteger = HDKeyDerivation.bigIntToBigInteger(BigInt('0x' + HDKeyDerivation.bufferToHex(randomBytes(32))));

    private constructor() { }

    /**
     * Child derivation may fail (although with extremely low probability); in such case it is re-attempted.
     * This is the maximum number of re-attempts (to avoid an infinite loop in case of bugs etc.).
     */
    public static readonly MAX_CHILD_DERIVATION_ATTEMPTS: number = 100;

    /**
     * Generates a new deterministic key from the given seed, which can be any arbitrary byte array.
     * 
     * @throws HDDerivationException if generated master key is invalid (private key 0 or >= n).
     * @throws Error if the seed is less than 8 bytes and could be brute forced.
     */
    public static createMasterPrivateKey(seed: Uint8Array): DeterministicKey {
        if (seed.length < 8) {
            throw new Error('Seed is too short and could be brute forced');
        }

        // Calculate I = HMAC-SHA512(key="Bitcoin seed", msg=S)
        const i = HDUtils.hmacSha512("Bitcoin seed", seed);
        
        // Split I into two 32-byte sequences, Il and Ir.
        // Use Il as master secret key, and Ir as master chain code.
        if (i.length !== 64) {
            throw new Error('Invalid HMAC-SHA512 output length');
        }
        
        const il = i.slice(0, 32);
        const ir = i.slice(32, 64);
        
        const masterPrivKey = HDKeyDerivation.createMasterPrivKeyFromBytes(il, ir);
        masterPrivKey.setCreationTimeSeconds(Math.floor(Date.now() / 1000));
        return masterPrivKey;
    }

    /**
     * @throws HDDerivationException if privKeyBytes is invalid (0 or >= n).
     */
    public static createMasterPrivKeyFromBytes(privKeyBytes: Uint8Array, chainCode: Uint8Array): DeterministicKey {
        const priv = HDKeyDerivation.bigIntToBigInteger(BigInt('0x' + HDKeyDerivation.bufferToHex(privKeyBytes)));
        HDKeyDerivation.assertNonZero(priv, 'Generated master key is invalid.');
        HDKeyDerivation.assertLessThanN(priv, 'Generated master key is invalid.');
        return new DeterministicKey([], chainCode, null, priv, null);
    }

    public static createMasterPubKeyFromBytes(pubKeyBytes: Uint8Array, chainCode: Uint8Array): DeterministicKey {
        const point = ECPoint.decodePoint(pubKeyBytes);
        return new DeterministicKey([], chainCode, point, null, null);
    }

    /**
     * Derives a key given the "extended" child number.
     */
    public static deriveChildKey(parent: DeterministicKey, childNumber: number | ChildNumber): DeterministicKey {
        const num = typeof childNumber === 'number' ? new ChildNumber(childNumber) : childNumber;
        return HDKeyDerivation.deriveChildKeyImpl(parent, num);
    }

    /**
     * Derives a key of the "extended" child number, ie. with the 0x80000000 bit specifying whether to use
     * hardened derivation or not. If derivation fails, tries a next child.
     */
    public static deriveThisOrNextChildKey(parent: DeterministicKey, childNumber: number): DeterministicKey {
        const isHardened = (childNumber & 0x80000000) !== 0;
        let nAttempts = 0;
        
        while (nAttempts < HDKeyDerivation.MAX_CHILD_DERIVATION_ATTEMPTS) {
            try {
                const child = new ChildNumber(childNumber + nAttempts, isHardened);
                return HDKeyDerivation.deriveChildKeyImpl(parent, child);
            } catch (e) {
                if (e instanceof HDDerivationException) {
                    nAttempts++;
                } else {
                    throw e;
                }
            }
        }
        
        throw new HDDerivationException('Maximum number of child derivation attempts reached.');
    }

    private static deriveChildKeyImpl(parent: DeterministicKey, childNumber: ChildNumber): DeterministicKey {
        if (!parent.hasPrivKey()) {
            const rawKey = HDKeyDerivation.deriveChildKeyBytesFromPublic(parent, childNumber, PublicDeriveMode.NORMAL);
            return new DeterministicKey(
                [...parent.getPath(), childNumber],
                rawKey.chainCode,
                ECPoint.decodePoint(rawKey.keyBytes),
                null,
                parent
            );
        } else {
            const rawKey = HDKeyDerivation.deriveChildKeyBytesFromPrivate(parent, childNumber);
            const privKey = HDKeyDerivation.bigIntToBigInteger(BigInt('0x' + HDKeyDerivation.bufferToHex(rawKey.keyBytes)));
            return new DeterministicKey(
                [...parent.getPath(), childNumber],
                rawKey.chainCode,
                null,
                privKey,
                parent
            );
        }
    }

    public static deriveChildKeyBytesFromPrivate(
        parent: DeterministicKey,
        childNumber: ChildNumber
    ): RawKeyBytes {
        if (!parent.hasPrivKey()) {
            throw new Error('Parent key must have private key bytes for this method.');
        }
        const parentPub = parent.getPubKeyPoint();
        if (!parentPub) throw new Error('Parent public key is missing');
        const parentPublicKey = parentPub.encode(true);
        if (parentPublicKey.length !== 33) {
            throw new Error(`Parent pubkey must be 33 bytes, but is ${parentPublicKey.length}`);
        }
        const data = new Uint8Array(37);
        if (childNumber.isHardened()) {
            const privKeyBytes = parent.getPrivKeyBytes33();
            data.set(privKeyBytes, 0);
        } else {
            data.set(parentPublicKey, 0);
        }
        const view = new DataView(data.buffer);
        view.setUint32(33, childNumber.getI(), false); // big-endian
        const i = HDUtils.hmacSha512Bytes(parent.getChainCode(), data);
        if (i.length !== 64) {
            throw new Error('Invalid HMAC-SHA512 output length');
        }
        const il = i.slice(0, 32);
        const chainCode = i.slice(32, 64);
        const ilInt = BigInt('0x' + HDKeyDerivation.bufferToHex(il));
        HDKeyDerivation.assertLessThanN(ilInt, 'Illegal derived key: I_L >= n');
        const priv = parent.getPrivKey();
        const N = ECKey.CURVE.n;
        const kiBigInt = (BigInt(priv.toString(10)) + ilInt) % N;
        HDKeyDerivation.assertNonZero(kiBigInt, 'Illegal derived key: derived private key equals 0.');
        const ki = HDKeyDerivation.bigIntToBigInteger(kiBigInt);
        const kiBytes = HDKeyDerivation.bigIntegerToBytes(ki, 32);
        return new RawKeyBytes(kiBytes, chainCode);
    }

    public static deriveChildKeyBytesFromPublic(
        parent: DeterministicKey,
        childNumber: ChildNumber,
        mode: PublicDeriveMode
    ): RawKeyBytes {
        if (childNumber.isHardened()) {
            throw new Error("Can't use private derivation with public keys only.");
        }
        const parentPub = parent.getPubKeyPoint();
        if (!parentPub) throw new Error('Parent public key is missing');
        const parentPublicKey = parentPub.encode(true);
        if (parentPublicKey.length !== 33) {
            throw new Error(`Parent pubkey must be 33 bytes, but is ${parentPublicKey.length}`);
        }
        const data = new Uint8Array(37);
        data.set(parentPublicKey, 0);
        const view = new DataView(data.buffer);
        view.setUint32(33, childNumber.getI(), false); // big-endian
        const i = HDUtils.hmacSha512Bytes(parent.getChainCode(), data);
        if (i.length !== 64) {
            throw new Error('Invalid HMAC-SHA512 output length');
        }
        const il = i.slice(0, 32);
        const chainCode = i.slice(32, 64);
        const ilInt = BigInt('0x' + HDKeyDerivation.bufferToHex(il));
        HDKeyDerivation.assertLessThanN(ilInt, 'Illegal derived key: I_L >= n');
        const N = ECKey.CURVE.n;
        let Ki: ECPoint;
        const parentPubPoint = parent.getPubKeyPoint();
        if (!parentPubPoint) throw new Error('Parent public key is missing');
        switch (mode) {
            case PublicDeriveMode.NORMAL:
                Ki = ECKey.publicPointFromPrivate(HDKeyDerivation.bigIntToBigInteger(ilInt)).add(parentPubPoint);
                break;
            case PublicDeriveMode.WITH_INVERSION: {
                const randIntBig = BigInt(HDKeyDerivation.RAND_INT.toString(10));
                const Ki1 = ECKey.publicPointFromPrivate(HDKeyDerivation.bigIntToBigInteger((ilInt + randIntBig) % N));
                const additiveInverse = (N - (randIntBig % N)) % N;
                const Ki2 = Ki1.add(ECKey.publicPointFromPrivate(HDKeyDerivation.bigIntToBigInteger(additiveInverse)));
                Ki = Ki2.add(parentPubPoint);
                break;
            }
            default:
                throw new Error('Invalid PublicDeriveMode');
        }
        HDKeyDerivation.assertNonInfinity(Ki, 'Illegal derived key: derived public key equals infinity.');
        return new RawKeyBytes(Ki.encode(true), chainCode);
    }

    private static assertNonZero(integer: BigInteger | bigint, errorMessage: string): void {
        if ((typeof integer === 'bigint' && integer === BigInt(0)) || (integer instanceof BigInteger && integer.compareTo(BigInteger.ZERO) === 0)) {
            throw new HDDerivationException(errorMessage);
        }
    }

    private static assertNonInfinity(point: ECPoint, errorMessage: string): void {
        if (point.isInfinity()) {
            throw new HDDerivationException(errorMessage);
        }
    }

    private static assertLessThanN(integer: BigInteger | bigint, errorMessage: string): void {
        const n = ECKey.CURVE.n;
        if ((typeof integer === 'bigint' && integer >= n) || (integer instanceof BigInteger && integer.compareTo(new BigInteger(n.toString())) >= 0)) {
            throw new HDDerivationException(errorMessage);
        }
    }
}

export enum PublicDeriveMode {
    NORMAL,
    WITH_INVERSION
}

export class RawKeyBytes {
    constructor(
        public readonly keyBytes: Uint8Array,
        public readonly chainCode: Uint8Array
    ) {}
}