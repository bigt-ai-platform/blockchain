import bigInt, { BigInteger } from 'big-integer';
import BN from 'bn.js';
import { VersionedChecksummedBytes } from './VersionedChecksummedBytes';
import { Utils } from './Utils';
import { ECDSASignature } from './ECDSASignature';
import { Sha256Hash } from './Sha256Hash';
import { Address } from './Address';
import { NetworkParameters } from './NetworkParameters';
import { DumpedPrivateKey } from './DumpedPrivateKey';
import { KeyCrypter } from './KeyCrypter';
import { EncryptedData } from './EncryptedData';
import { ec } from 'elliptic';
import { KeyParameter } from './KeyParameter';

const secp256k1 = new ec('secp256k1');

export class ECKey extends VersionedChecksummedBytes {
    private static readonly logger = console; // Replace with actual logger if available

    private priv: BigInteger | null;
    private pub: Uint8Array;
    private creationTimeSeconds: number = 0;
    private keyCrypter: KeyCrypter | null = null;
    private encryptedPrivateKey: EncryptedData | null = null;
    private pubKeyHash: Uint8Array | null = null;

    constructor(priv: BigInteger | null = null, pub: Uint8Array, version: number = 0) {
        super(version, pub);
        this.priv = priv;
        this.pub = pub;
    }

    // =============================================================================================
    // Key Generation and Import/Export
    // =============================================================================================

    public static fromPrivate(privateKey: BigInteger, compressed: boolean = true): ECKey {
        const pubKey = ECKey.publicKeyFromPrivate(privateKey, compressed);
        return new ECKey(privateKey, pubKey);
    }

    public static fromPrivateBytes(privateKeyBytes: Uint8Array, compressed: boolean = true): ECKey {
        const privateKey = Utils.bytesToBigInteger(privateKeyBytes);
        return ECKey.fromPrivate(privateKey, compressed);
    }

    public static fromPublicOnly(publicKey: Uint8Array): ECKey {
        return new ECKey(null, publicKey);
    }

    public static fromEncrypted(
        encryptedPrivateKey: EncryptedData, 
        keyCrypter: KeyCrypter, 
        publicKey: Uint8Array
    ): ECKey {
        const key = ECKey.fromPublicOnly(publicKey);
        key.encryptedPrivateKey = encryptedPrivateKey;
        key.keyCrypter = keyCrypter;
        return key;
    }

    // =============================================================================================
    // Cryptographic Operations
    // =============================================================================================

    public sign(hash: Sha256Hash): ECDSASignature {
        if (!this.priv) {
            throw new Error("Private key not available for signing");
        }
        
        // Create elliptic key from private key
        const key = secp256k1.keyFromPrivate(this.priv.toString());
        
        // Sign the hash
        const signature = key.sign(hash.getBytes());
        
        // Return as ECDSASignature
        return new ECDSASignature(
            bigInt(signature.r.toString()),
            bigInt(signature.s.toString()),
            signature.recoveryParam ?? undefined
        );
    }

    public verify(hash: Sha256Hash, signature: ECDSASignature): boolean {
        try {
            // Create elliptic key from public key
            const key = secp256k1.keyFromPublic(this.pub);
            
            // Create elliptic signature object
            const ellipticSig = {
                r: signature.r.toString(16),
                s: signature.s.toString(16),
                recoveryParam: signature.recoveryParam
            };
            
            // Verify the signature
            return key.verify(hash.getBytes(), ellipticSig);
        } catch (e) {
            return false;
        }
    }

    public static verify(data: Uint8Array, signature: ECDSASignature, publicKey: Uint8Array): boolean {
        try {
            // Create elliptic key from public key
            const key = secp256k1.keyFromPublic(publicKey);
            
            // Verify the signature using the r and s values
            return key.verify(data, {
                r: signature.r.toString(16),
                s: signature.s.toString(16)
            });
        } catch (e) {
            return false;
        }
    }

    // =============================================================================================
    // Key Properties and Utilities
    // =============================================================================================

    public getPubKey(): Uint8Array {
        return this.pub;
    }

    public getPubKeyHash(): Uint8Array {
        if (!this.pubKeyHash) {
            this.pubKeyHash = Utils.sha256hash160(this.pub);
        }
        return this.pubKeyHash;
    }

    public toAddress(params: NetworkParameters): Address {
        return new Address(params, this.getPubKeyHash());
    }

    public getPrivKey(): BigInteger {
        if (!this.priv) {
            throw new Error("Private key not available");
        }
        return this.priv;
    }

    public getPrivKeyBytes(): Uint8Array {
        return Utils.bigIntegerToBytes(this.getPrivKey(), 32);
    }

    public isCompressed(): boolean {
        return this.pub.length === 33;
    }

    // =============================================================================================
    // Message Signing/Verification
    // =============================================================================================

    public signMessage(message: string): ECDSASignature {
        if (!this.priv) {
            throw new Error("Private key not available for signing");
        }
        
        // Format message and create double SHA256 hash (Bitcoin standard)
        const data = Utils.formatMessageForSigning(message);
        const hash = Sha256Hash.twiceOf(data);
        
        // Sign the hash
        return this.sign(hash);
    }

    public verifyMessage(message: string, signature: ECDSASignature): boolean {
        // Format message and create double SHA256 hash (Bitcoin standard)
        const data = Utils.formatMessageForSigning(message);
        const hash = Sha256Hash.twiceOf(data);
        
        // Verify the signature
        const result = this.verify(hash, signature);
        
        // Debug output
        console.log("Message:", message);
        console.log("Formatted data:", Utils.bytesToHex(data));
        console.log("Hash:", hash.toString());
        console.log("Signature:", signature.r.toString(), signature.s.toString());
        console.log("Public key:", Utils.bytesToHex(this.pub));
        console.log("Verification result:", result);
        
        return result;
    }

    public static signedMessageToKey(message: string, signatureBase64: string): ECKey {
        const data = Utils.formatMessageForSigning(message);
        const signatureBytes = Utils.base64ToBytes(signatureBase64);
        const signature = ECDSASignature.decodeFromDER(signatureBytes);
        const hash = Sha256Hash.of(data);
        
        // Try recovery with different recIds
        for (let recId = 0; recId < 4; recId++) {
            try {
                const key = ECKey.recoverFromSignature(recId, signature, hash, true);
                if (key) {
                    return key;
                }
            } catch (e) {
                // Try next recovery ID
            }
        }
        throw new Error("Could not recover public key from signature");
    }

    // =============================================================================================
    // Encryption/Decryption
    // =============================================================================================

    public isEncrypted(): boolean {
        return this.keyCrypter !== null && 
               this.encryptedPrivateKey !== null && 
               this.encryptedPrivateKey.encryptedBytes.length > 0;
    }

    public encrypt(keyCrypter: KeyCrypter, aesKey: Uint8Array): ECKey {
        if (!this.priv) {
            throw new Error("Private key not available for encryption");
        }
        const privKeyBytes = this.getPrivKeyBytes();
        const encryptedPrivateKey = keyCrypter.encrypt(privKeyBytes, new KeyParameter(aesKey));
        return ECKey.fromEncrypted(encryptedPrivateKey, keyCrypter, this.pub);
    }

    public decrypt(aesKey: Uint8Array): ECKey {
        if (!this.keyCrypter || !this.encryptedPrivateKey) {
            throw new Error("Key is not encrypted");
        }
        const unencryptedPrivateKey = this.keyCrypter.decrypt(this.encryptedPrivateKey, new KeyParameter(aesKey));
        return ECKey.fromPrivateBytes(unencryptedPrivateKey, this.isCompressed());
    }

    // =============================================================================================
    // Key Recovery
    // =============================================================================================

    public static recoverFromSignature(
        recId: number, 
        sig: ECDSASignature, 
        message: Sha256Hash, 
        compressed: boolean
    ): ECKey | null {
        // Clamp recovery ID to valid range (0-3)
        const clampedRecId = recId & 3;
        
        // Use elliptic's built-in recovery
        const signature = {
            r: sig.r.toString(16),
            s: sig.s.toString(16),
            recoveryParam: clampedRecId
        };
        const msgHex = Utils.bytesToHex(message.getBytes());
        const pubKeyPoint = secp256k1.recoverPubKey(msgHex, signature, signature.recoveryParam, 'hex');
        if (!pubKeyPoint) {
            return null;
        }
        return ECKey.fromPublicOnly(new Uint8Array(pubKeyPoint.encode(compressed, 'array')));
    }

    private static decompressKey(x: BigInteger, yBit: boolean): any | null {
        try {
            const xBytes = Utils.bigIntegerToBytes(x, 32);
            const prefix = yBit ? 0x03 : 0x02;
            const keyBytes = new Uint8Array(33);
            keyBytes[0] = prefix;
            keyBytes.set(xBytes, 1);
            return secp256k1.curve.decodePoint(keyBytes);
        } catch (e) {
            return null;
        }
    }

    // =============================================================================================
    // Static Helper Methods
    // =============================================================================================

    public static publicKeyFromPrivate(privateKey: BigInteger, compressed: boolean): Uint8Array {
        const key = secp256k1.keyFromPrivate(privateKey.toString());
        return new Uint8Array(key.getPublic(compressed, 'array'));
    }

    public static create(): ECKey {
        const keyPair = secp256k1.genKeyPair();
        const privateKey = bigInt(keyPair.getPrivate().toString());
        const publicKey = new Uint8Array(keyPair.getPublic(true, 'array'));
        return new ECKey(privateKey, publicKey, 0); // Use default version
    }

    public static isPubKeyCanonical(pubkey: Uint8Array): boolean {
        if (pubkey.length < 33) return false;
        if (pubkey[0] === 0x04) {
            return pubkey.length === 65; // Uncompressed
        } else if (pubkey[0] === 0x02 || pubkey[0] === 0x03) {
            return pubkey.length === 33; // Compressed
        }
        return false;
    }

    // =============================================================================================
    // Additional Methods
    // =============================================================================================

    public getCreationTimeSeconds(): number {
        return this.creationTimeSeconds;
    }

    public setCreationTimeSeconds(seconds: number): void {
        if (seconds < 0) throw new Error("Creation time cannot be negative");
        this.creationTimeSeconds = seconds;
    }

    public getKeyCrypter(): KeyCrypter | null {
        return this.keyCrypter;
    }

    public getEncryptedPrivateKey(): EncryptedData | null {
        return this.encryptedPrivateKey;
    }

    public isPubKeyOnly(): boolean {
        return this.priv === null;
    }

    public hasPrivKey(): boolean {
        return this.priv !== null;
    }

    public isWatching(): boolean {
        return this.isPubKeyOnly() && !this.isEncrypted();
    }

    public getPrivateKeyEncoded(params: NetworkParameters): DumpedPrivateKey {
        return new DumpedPrivateKey(params, this.getPrivKeyBytes(), this.isCompressed());
    }

    public toString(): string {
        return this.toStringWithPrivate();
    }

    public toStringWithPrivate(): string {
        let str = `ECKey(pub=${Utils.bytesToHex(this.getPubKey())}`;
        if (this.priv) {
            str += `, priv=${Utils.bytesToHex(this.getPrivKeyBytes())}`;
        }
        if (this.creationTimeSeconds > 0) {
            str += `, created=${new Date(this.creationTimeSeconds * 1000).toISOString()}`;
        }
        str += ')';
        return str;
    }
}
