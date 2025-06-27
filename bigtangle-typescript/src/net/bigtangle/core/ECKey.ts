import { BigInteger } from './BigInteger';
import { secp256k1 } from '@noble/curves/secp256k1';
import { sha256 } from '@noble/hashes/sha256';
import { ripemd160 } from '@noble/hashes/ripemd160';
import { ECDSASignature } from '../core/ECDSASignature';
import { ECPoint } from './ECPoint';
import * as NetworkParameters from './NetworkParameters';
import * as Address from './Address';
import { KeyParameter, KeyCrypter } from '../crypto/KeyCrypter';
import { EncryptedData } from '../crypto/EncryptedData';

export class ECKey {
    public static readonly CURVE = secp256k1.CURVE;

    // Helper to convert BigInteger to 32-byte Uint8Array
    private static bigIntToBytes(bi: BigInteger, length: number = 32): Uint8Array {
        let hex = bi.toString(16);
        if (hex.length % 2 !== 0) hex = '0' + hex;
        let bytes = Buffer.from(hex, 'hex');
        if (bytes.length < length) {
            // Pad with zeros
            const pad = Buffer.alloc(length - bytes.length, 0);
            bytes = Buffer.concat([pad, bytes]);
        }
        return new Uint8Array(bytes);
    }

    // Helper to convert Buffer or Uint8Array to hex string
    private static bufferToHex(buf: Buffer | Uint8Array): string {
        return Buffer.from(buf).toString('hex');
    }

    protected priv: BigInteger | null;
    public pub: ECPoint | null;
    protected creationTimeSeconds: number = 0;
    protected encryptedPrivateKey: EncryptedData | null = null;
    protected keyCrypter: KeyCrypter | null = null;

    constructor(priv: BigInteger | null, pub: ECPoint | null) {
        this.priv = priv;
        this.pub = pub;
    }

    public static fromPrivate(privKey: BigInteger): ECKey {
        const pubPoint = ECKey.publicPointFromPrivate(privKey);
        return new ECKey(privKey, pubPoint);
    }

    public static fromPublic(pubKeyBytes: Uint8Array): ECKey {
        const pubPoint = ECPoint.decodePoint(pubKeyBytes);
        return new ECKey(null, pubPoint);
    }

    public static publicPointFromPrivate(privKey: BigInteger): ECPoint {
        const pubKey = secp256k1.getPublicKey(ECKey.bigIntToBytes(privKey, 32));
        return ECPoint.decodePoint(pubKey);
    }

    public getPrivKeyBytes(): Uint8Array {
        if (!this.priv) {
            throw new Error("Private key is not available");
        }
        return ECKey.bigIntToBytes(this.priv, 32);
    }

    public getPubKeyBytes(): Uint8Array {
        if (!this.pub) {
            throw new Error("Public key is not available");
        }
        return this.pub.encode(true); // Compressed public key
    }

    public getPubKeyPoint(): ECPoint | null {
        return this.pub;
    }

    public getPubKeyHash(): Uint8Array {
        return ripemd160(sha256(this.getPubKeyBytes()));
    }

    public sign(messageHash: Uint8Array, aesKey?: KeyParameter): ECDSASignature {
        if (this.isEncrypted()) {
            if (!aesKey) {
                throw new Error("AES key is required for signing an encrypted key");
            }
            if (!this.keyCrypter || !this.encryptedPrivateKey) {
                throw new Error("KeyCrypter or encrypted private key missing");
            }
            const decryptedPrivKey = this.keyCrypter.decrypt(this.encryptedPrivateKey, aesKey);
            const hex = ECKey.bufferToHex(decryptedPrivKey);
            return this.doSign(messageHash, new BigInteger(hex, 16));
        } else {
            if (!this.priv) {
                throw new Error("Private key is not available for signing");
            }
            return this.doSign(messageHash, this.priv);
        }
    }

    public doSign(messageHash: Uint8Array, privKey: BigInteger): ECDSASignature {
        const signature = secp256k1.sign(messageHash, ECKey.bigIntToBytes(privKey, 32));
        // Convert BigInteger to bigint for ECDSASignature
        const rBigInt = BigInt(signature.r.toString());
        const sBigInt = BigInt(signature.s.toString());
        return new ECDSASignature(rBigInt, sBigInt);
    }

    public verify(messageHash: Uint8Array, signature: ECDSASignature): boolean {
        if (!this.pub) {
            throw new Error("Public key is not available for verification");
        }
        return secp256k1.verify(
            { r: BigInt(signature.r.toString()), s: BigInt(signature.s.toString()) },
            messageHash,
            this.pub.encode(true)
        );
    }

    public isPubKeyOnly(): boolean {
        return this.priv === null;
    }

    public isEncrypted(): boolean {
        return this.encryptedPrivateKey !== null;
    }

    public getCreationTimeSeconds(): number {
        return this.creationTimeSeconds;
    }

    public setCreationTimeSeconds(creationTimeSeconds: number): void {
        this.creationTimeSeconds = creationTimeSeconds;
    }

    public encrypt(keyCrypter: KeyCrypter, aesKey: KeyParameter): ECKey {
        if (!this.priv) {
            throw new Error("Private key is not available for encryption");
        }
        const encryptedData = keyCrypter.encrypt(this.getPrivKeyBytes(), aesKey);
        const encryptedKey = new ECKey(null, this.pub);
        encryptedKey.encryptedPrivateKey = encryptedData;
        encryptedKey.keyCrypter = keyCrypter;
        encryptedKey.creationTimeSeconds = this.creationTimeSeconds;
        return encryptedKey;
    }

    public decrypt(keyCrypter: KeyCrypter, aesKey: KeyParameter): ECKey {
        if (!this.encryptedPrivateKey || !this.keyCrypter) {
            throw new Error("Key is not encrypted or keyCrypter is missing");
        }
        if (!this.keyCrypter.equals(keyCrypter)) {
            throw new Error("KeyCrypter mismatch");
        }
        const decryptedPrivKeyBytes = keyCrypter.decrypt(this.encryptedPrivateKey, aesKey);
        const hex = ECKey.bufferToHex(decryptedPrivKeyBytes);
        const decryptedPrivKey = new BigInteger(hex, 16);
        const decryptedKey = new ECKey(decryptedPrivKey, this.pub);
        decryptedKey.creationTimeSeconds = this.creationTimeSeconds;
        return decryptedKey;
    }

    public equals(other: any): boolean {
        if (!(other instanceof ECKey)) return false;
        return (this.priv === null || other.priv === null || this.priv.equals(other.priv)) &&
               (this.pub === null || other.pub === null || this.pub.equals(other.pub));
    }

    public hashCode(): number {
        let result = 17;
        if (this.priv) {
            // Use hex string and hash it simply
            const hex = this.priv.toString(16);
            for (let i = 0; i < hex.length; i++) {
                result = 31 * result + hex.charCodeAt(i);
            }
        }
        if (this.pub) {
            result = 31 * result + this.pub.hashCode();
        }
        return result;
    }

    public toAddress(params: NetworkParameters.NetworkParameters): Address.Address {
        const version = params.getAddressHeader();
        return new Address.Address(params, version, Buffer.from(this.getPubKeyHash()));
    }

    public static encryptionIsReversible(originalKey: ECKey, encryptedKey: ECKey, keyCrypter: KeyCrypter, aesKey: KeyParameter): boolean {
        try {
            const decryptedKey = encryptedKey.decrypt(keyCrypter, aesKey);
            return originalKey.equals(decryptedKey);
        // eslint-disable-next-line no-empty
        } catch (e: any) {
            // Exception intentionally caught for reversibility check; do not rethrow.
            return false;
        }
    }

    public isWatching(): boolean {
        // Placeholder for isWatching logic
        return this.isPubKeyOnly();
    }
}