import { BigInteger } from '../core/BigInteger';
import { ECKey } from '../core/ECKey';
import { ECPoint } from '../core/ECPoint';
import { Digest } from './ConcatKDFBytesGenerator';
import { hmac } from '@noble/hashes/hmac';
import { sha256 } from '@noble/hashes/sha256';
import aes from '@noble/ciphers/aes';
import { secp256k1 } from '@noble/curves/secp256k1';
import { Utils } from '../utils/Utils';

// --- Interfaces --- //

export interface BasicAgreement {
    init(privKey: CipherParameters): void;
    calculateAgreement(pubKey: CipherParameters): BigInteger;
    getFieldSize(): number;
}

export interface DerivationFunction {
    init(param: DerivationParameters): void;
    generateBytes(out: Uint8Array, outOff: number, len: number): number;
}

export interface Mac {
    init(key: CipherParameters): void;
    update(input: Uint8Array, inOff: number, len: number): void;
    doFinal(out: Uint8Array, outOff: number): number;
    getMacSize(): number;
    reset(): void;
}

export interface BufferedBlockCipher {
    init(forEncryption: boolean, params: CipherParameters): void;
    processBytes(input: Uint8Array, inOff: number, len: number, output: Uint8Array, outOff: number): number;
    doFinal(output: Uint8Array, outOff: number): number;
    getOutputSize(len: number): number;
    getUpdateOutputSize(len: number): number;
    reset(): void;
}

export interface CipherParameters {}

export class KeyParameter implements CipherParameters {
    constructor(public key: Uint8Array) {}
}

export class ParametersWithIV implements CipherParameters {
    constructor(public parameters: CipherParameters, public iv: Uint8Array) {}
}

export interface DerivationParameters {}

export class IESParameters implements DerivationParameters {
    constructor(
        public derivationV: Uint8Array | null,
        public encodingV: Uint8Array | null,
        public macKeySize: number,
        public cipherKeySize?: number // Optional for stream ciphers
    ) {}
}

export class IESWithCipherParameters extends IESParameters {
    // Useless constructor removed; base class handles all
}

export interface KeyParser {
    readKey(input: Uint8Array): CipherParameters;
}

export interface EphemeralKeyPair {
    getKeyPair(): { getPrivate(): CipherParameters, getPublic(): CipherParameters };
    getEncodedPublicKey(): Uint8Array;
}

export interface EphemeralKeyPairGenerator {
    generate(): EphemeralKeyPair;
}

// --- Implementations --- //

export class ECDHBasicAgreement implements BasicAgreement {
    private readonly curve = ECKey.CURVE;
    private privateKey: BigInteger | null = null;

    init(privKey: CipherParameters): void {
        if (privKey instanceof ECPrivateKeyParameters) {
            this.privateKey = privKey.d;
        } else {
            throw new Error("Invalid private key parameters");
        }
    }

    calculateAgreement(pubKey: CipherParameters): BigInteger {
        if (!this.privateKey) {
            throw new Error("Private key not initialized");
        }
        if (pubKey instanceof ECPublicKeyParameters) {
            const sharedSecret = secp256k1.getSharedSecret(
                Utils.bigIntToBytes(this.privateKey, 32),
                pubKey.q.encode(false)
            );
            return new BigInteger(Utils.HEX.encode(sharedSecret), 16);
        } else {
            throw new Error("Invalid public key parameters");
        }
    }

    getFieldSize(): number {
        // Return the bit length of the curve order n
        return secp256k1.CURVE.n.toString(2).length;
    }
}

export class HMacSHA256 implements Mac {
    private key: Uint8Array | null = null;
    private readonly _macSize: number = 32; // SHA256 HMAC size

    init(key: CipherParameters): void {
        if (key instanceof KeyParameter) {
            this.key = key.key;
        } else {
            throw new Error("Invalid key parameter for HMAC");
        }
    }

    update(input: Uint8Array, inOff: number, len: number): void {
        // No-op for one-shot HMAC
    }

    doFinal(out: Uint8Array, outOff: number): number {
        if (!this.key) throw new Error("HMAC key not initialized");
        // One-shot HMAC for the entire buffer
        const result = hmac(sha256, this.key, out.slice(outOff));
        out.set(result, outOff);
        this.reset();
        return result.length;
    }

    getMacSize(): number {
        return this._macSize;
    }

    reset(): void {
        this.key = null;
    }
}

export class AESEngine implements BufferedBlockCipher {
    private _forEncryption: boolean = false;
    private _key: Uint8Array | null = null;
    private _iv: Uint8Array | null = null;

    init(forEncryption: boolean, params: CipherParameters): void {
        this._forEncryption = forEncryption;
        if (params instanceof ParametersWithIV) {
            this._key = (params.parameters as KeyParameter).key;
            this._iv = params.iv;
        } else if (params instanceof KeyParameter) {
            this._key = params.key;
            this._iv = null; // No IV for this mode
        } else {
            throw new Error("Invalid parameters for AESEngine");
        }
    }

    processBytes(input: Uint8Array, inOff: number, len: number, output: Uint8Array, outOff: number): number {
        if (!this._key) throw new Error("AESEngine not initialized: missing key");
        if (this._iv === null) throw new Error("AESEngine not initialized: missing IV");
        const data = input.slice(inOff, inOff + len);
        // Use aes.ctr for CTR mode
        const cipher = aes.ctr(this._key, this._iv);
        const result = this._forEncryption ? cipher.encrypt(data) : cipher.decrypt(data);
        output.set(result, outOff);
        return result.length;
    }

    doFinal(output: Uint8Array, outOff: number): number {
        // For CTR mode, doFinal typically doesn't do anything extra after processBytes
        return 0;
    }

    getOutputSize(len: number): number {
        return len;
    }

    getUpdateOutputSize(len: number): number {
        return len;
    }

    reset(): void {
        this._key = null;
        this._iv = null;
    }
}

export class ECPrivateKeyParameters implements CipherParameters {
    constructor(public d: BigInteger, public curve: any) {}
}

export class ECPublicKeyParameters implements CipherParameters {
    constructor(public q: ECPoint, public curve: any) {}
}

// --- IESEngine Class --- //

export class IESEngine {
    private readonly hash: Digest;
    private readonly agree: BasicAgreement;
    private readonly kdf: DerivationFunction;
    private readonly mac: Mac;
    private readonly cipher: BufferedBlockCipher | null;
    private readonly macBuf: Uint8Array;

    private forEncryption: boolean = false;
    private privParam: CipherParameters | null = null;
    private pubParam: CipherParameters | null = null;
    private param: IESParameters | null = null;

    private V: Uint8Array = new Uint8Array(0);
    private keyPairGenerator: EphemeralKeyPairGenerator | null = null;
    private keyParser: KeyParser | null = null;
    private IV: Uint8Array | null = null;
    private hashK2: boolean = true;

    constructor(
        agree: BasicAgreement,
        kdf: DerivationFunction,
        mac: Mac,
        hash: Digest,
        cipher: BufferedBlockCipher | null
    ) {
        this.agree = agree;
        this.kdf = kdf;
        this.mac = mac;
        this.hash = hash;
        this.macBuf = new Uint8Array(mac.getMacSize());
        this.cipher = cipher;
    }

    public setHashMacKey(hashK2: boolean): void {
        this.hashK2 = hashK2;
    }

    public init(
        forEncryption: boolean,
        privParam: CipherParameters,
        pubParam: CipherParameters,
        params: CipherParameters
    ): void;
    public init(
        publicKey: CipherParameters,
        params: CipherParameters,
        ephemeralKeyPairGenerator: EphemeralKeyPairGenerator
    ): void;
    public init(
        privateKey: CipherParameters,
        params: CipherParameters,
        publicKeyParser: KeyParser
    ): void;
    public init(...args: any[]): void {
        if (args.length === 4) {
            const [forEncryption, privParam, pubParam, params] = args;
            this.forEncryption = forEncryption;
            this.privParam = privParam;
            this.pubParam = pubParam;
            this.V = new Uint8Array(0);
            this.extractParams(params);
        } else if (args.length === 3) {
            const [keyParam, params, thirdArg] = args;
            if (thirdArg && typeof thirdArg.generate === 'function') { // EphemeralKeyPairGenerator
                this.forEncryption = true;
                this.pubParam = keyParam;
                this.keyPairGenerator = thirdArg;
                this.extractParams(params);
            } else if (thirdArg && typeof thirdArg.readKey === 'function') { // KeyParser
                this.forEncryption = false;
                this.privParam = keyParam;
                this.keyParser = thirdArg;
                this.extractParams(params);
            } else {
                throw new Error("Invalid init parameters");
            }
        } else {
            throw new Error("Invalid number of arguments for init");
        }
    }

    private extractParams(params: CipherParameters): void {
        if (params instanceof ParametersWithIV) {
            this.IV = params.iv;
            this.param = params.parameters as IESParameters;
        } else {
            this.IV = null;
            this.param = params as IESParameters;
        }
    }

    public getCipher(): BufferedBlockCipher | null {
        return this.cipher;
    }

    public getMac(): Mac {
        return this.mac;
    }

    // Helper: BigInteger to Uint8Array (length bytes)
    private static bigIntegerToBytes(bi: BigInteger, length: number): Uint8Array {
        let hex = bi.toString(16);
        if (hex.length % 2 !== 0) hex = '0' + hex;
        let bytes = Buffer.from(hex, 'hex');
        if (bytes.length < length) {
            const pad = Buffer.alloc(length - bytes.length, 0);
            bytes = Buffer.concat([pad, bytes]);
        }
        return new Uint8Array(bytes);
    }
    // Helper: compare two Uint8Array
    private static bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
        if (a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) {
            if (a[i] !== b[i]) return false;
        }
        return true;
    }
}
