import { ECKey } from '../core/ECKey';
import { ChildNumber } from './ChildNumber';
import { ECPoint } from '../core/ECPoint';
import { ECDSASignature } from './ECDSASignature';
import { Sha256Hash } from '../core/Sha256Hash'; // Corrected import path
import { HDKeyDerivation } from './HDKeyDerivation';
import { NetworkParameters } from '../params/NetworkParameters'; // Assuming this will be implemented or replaced
import { Utils } from '../utils/Utils';
import { Base58 } from '../utils/Base58';
import { HDUtils } from './HDUtils';
import { MissingPrivateKeyException } from './MissingPrivateKeyException';
import { KeyCrypter, KeyParameter } from './KeyCrypter';
import { EncryptedData } from './EncryptedData';
import { BigInteger } from '../core/BigInteger';

/**
 * A deterministic key is a node in a {@link DeterministicHierarchy}. As per
 * <a href="https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki">the BIP 32 specification</a> it is a pair
 * (key, chaincode). If you know its path in the tree and its chain code you can derive more keys from this.
 */
export class DeterministicKey extends ECKey {
    /** Sorts deterministic keys in the order of their child number. */
    public static readonly CHILDNUM_ORDER = (k1: ECKey, k2: ECKey) => {
        const cn1 = (k1 as unknown as DeterministicKey).getChildNumber();
        const cn2 = (k2 as unknown as DeterministicKey).getChildNumber();
        return cn1.compareTo(cn2);
    };

    private readonly parent: DeterministicKey | null;
    private readonly childNumberPath: ChildNumber[];
    private readonly depth: number;
    private parentFingerprint: number; // 0 if this key is root node of key hierarchy
    private readonly chainCode: Uint8Array;

    protected encryptedPrivateKey: EncryptedData | null = null;
    protected keyCrypter: KeyCrypter | null = null;

    // Helper to convert bigint to BigInteger
    private static bigIntToBigInteger(bi: bigint): BigInteger {
        return new BigInteger(bi.toString(16), 16);
    }
    // Helper to convert Uint8Array to hex string (public static, renamed)
    public static detBufferToHex(buf: Uint8Array): string {
        return Buffer.from(buf).toString('hex');
    }
    // Helper to compare two Uint8Arrays
    private static bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
        if (a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) {
            if (a[i] !== b[i]) return false;
        }
        return true;
    }
    // Helper to convert BigInteger to Uint8Array (32 bytes)
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

    // Constructor for creating a new DeterministicKey
    constructor(
        childNumberPath: ChildNumber[],
        chainCode: Uint8Array,
        publicAsPoint: ECPoint | null,
        priv: BigInteger | null,
        parent: DeterministicKey | null,
        depth?: number,
        parentFingerprint?: number,
        keyCrypter?: KeyCrypter,
        encryptedPrivateKey?: EncryptedData
    ) {
        super(
            priv ?? null,
            priv !== null ? ECKey.publicPointFromPrivate(priv) : publicAsPoint
        );
        if (chainCode.length !== 32) throw new Error('Chain code must be 32 bytes');
        this.parent = parent;
        this.childNumberPath = [...childNumberPath];
        this.chainCode = new Uint8Array(chainCode);
        this.depth = depth ?? (parent ? parent.depth + 1 : 0);
        this.parentFingerprint = parentFingerprint ?? (parent ? parent.getFingerprint() : 0);
        if (keyCrypter && encryptedPrivateKey) {
            this.keyCrypter = keyCrypter;
            this.encryptedPrivateKey = encryptedPrivateKey;
        }
    }

    /**
     * Clones the key
     */
    public static fromOtherKey(keyToClone: DeterministicKey, newParent: DeterministicKey): DeterministicKey {
        const newKey = new DeterministicKey(
            keyToClone.childNumberPath,
            keyToClone.chainCode,
            keyToClone.pub,
            keyToClone.priv,
            newParent,
            keyToClone.childNumberPath.length, // Depth is path length for cloned keys
            newParent.getFingerprint(),
            keyToClone.keyCrypter || undefined,
            keyToClone.encryptedPrivateKey || undefined
        );
        newKey.setCreationTimeSeconds(keyToClone.getCreationTimeSeconds());
        return newKey;
    }

    /**
     * Returns the path through some {@link DeterministicHierarchy} which reaches this keys position in the tree.
     */
    public getPath(): ChildNumber[] {
        return [...this.childNumberPath];
    }

    /**
     * Returns the path of this key as a human readable string starting with M to indicate the master key.
     */
    public getPathAsString(): string {
        return HDUtils.formatPath(this.getPath());
    }

    /**
     * Return this key's depth in the hierarchy, where the root node is at depth zero.
     */
    public getDepth(): number {
        return this.depth;
    }

    /** Returns the last element of the path */
    public getChildNumber(): ChildNumber {
        return this.childNumberPath.length === 0 ? 
            ChildNumber.ZERO : 
            this.childNumberPath[this.childNumberPath.length - 1];
    }

    /**
     * Returns the chain code associated with this key.
     */
    public getChainCode(): Uint8Array {
        return new Uint8Array(this.chainCode);
    }

    /**
     * Returns RIPE-MD160(SHA256(pub key bytes)).
     */
    public getIdentifier(): Uint8Array {
        // Assuming Sha256Hash.hashTwice and Utils.sha256hash160 are available
        return Utils.sha256hash160(this.getPubKeyBytes());
    }

    /** Returns the first 32 bits of the result of {@link #getIdentifier()}. */
    public getFingerprint(): number {
        const identifier = this.getIdentifier();
        // Use DataView to read the first 4 bytes as an Int32 (big-endian by default for getInt32)
        return new DataView(identifier.buffer, identifier.byteOffset, identifier.byteLength).getInt32(0, false);
    }

    public getParent(): DeterministicKey | null {
        return this.parent;
    }

    /**
     * Return the fingerprint of the key from which this key was derived.
     */
    public getParentFingerprint(): number {
        return this.parentFingerprint;
    }

    /**
     * Returns private key bytes, padded with zeros to 33 bytes.
     * @throws IllegalStateException if the private key bytes are missing.
     */
    public getPrivKeyBytes33(): Uint8Array {
        const privBytes = this.getPrivKeyBytes();
        if (!privBytes) throw new Error("Private key bytes are missing.");
        const bytes33 = new Uint8Array(33);
        bytes33.set(privBytes, 33 - privBytes.length);
        return bytes33;
    }

    /**
     * Returns the same key with the private bytes removed.
     */
    public dropPrivateBytes(): DeterministicKey {
        if (this.isPubKeyOnly()) return this;
        return new DeterministicKey(
            this.getPath(),
            this.getChainCode(),
            this.pub,
            null,
            this.parent
        );
    }

    /**
     * <p>Returns the same key with the parent pointer removed (it still knows its own path and the parent fingerprint).</p>
     *
     * <p>If this key doesn't have private key bytes stored/cached itself, but could rederive them from the parent, then
     * the new key returned by this method won't be able to do that. Thus, using dropPrivateBytes().dropParent() on a
     * regular DeterministicKey will yield a new DeterministicKey that cannot sign or do other things involving the
     * private key at all.</p>
     */
    public dropParent(): DeterministicKey {
        const key = new DeterministicKey(
            this.getPath(),
            this.getChainCode(),
            this.pub,
            this.priv,
            null // parent is null
        );
        key.parentFingerprint = this.parentFingerprint;
        return key;
    }

    static addChecksum(input: Uint8Array): Uint8Array {
        const checksummed = new Uint8Array(input.length + 4);
        checksummed.set(input, 0);
        // Convert to Buffer for hashTwice
        const checksum = Sha256Hash.hashTwice(Buffer.from(input));
        checksummed.set(checksum.slice(0, 4), input.length);
        return checksummed;
    }

    // Ensure encrypt matches base class signature
    public encrypt(keyCrypter: KeyCrypter, aesKey: KeyParameter): ECKey {
        return this.encryptDeterministic(keyCrypter, aesKey, null);
    }
    // DeterministicKey-specific encrypt
    public encryptDeterministic(keyCrypter: KeyCrypter, aesKey: KeyParameter, newParent: DeterministicKey | null = null): DeterministicKey {
        if (newParent !== null) {
            if (!newParent.isEncrypted()) {
                throw new Error("New parent must be encrypted.");
            }
        }
        const privKeyBytes = this.getPrivKeyBytes();
        if (!privKeyBytes) throw new Error("Private key is not available");
        const encryptedPrivateKey = keyCrypter.encrypt(privKeyBytes, aesKey);
        const key = new DeterministicKey(
            this.childNumberPath,
            this.chainCode,
            this.pub,
            null,
            newParent,
            undefined,
            undefined,
            keyCrypter,
            encryptedPrivateKey
        );
        if (!newParent) {
            key.setCreationTimeSeconds(this.getCreationTimeSeconds());
        }
        return key;
    }

    /**
     * A deterministic key is considered to be 'public key only' if it hasn't got a private key part and it cannot be
     * rederived. If the hierarchy is encrypted this returns true.
     */
    public isPubKeyOnly(): boolean {
        return super.isPubKeyOnly() && (this.parent === null || this.parent.isPubKeyOnly());
    }

    /** {@inheritDoc} */
    public hasPrivKey(): boolean {
        return this.findParentWithPrivKey() !== null;
    }

    public getSecretBytes(): Uint8Array | null {
        return this.priv ? this.getPrivKeyBytes() : null;
    }

    /**
     * A deterministic key is considered to be encrypted if it has access to encrypted private key bytes, OR if its
     * parent does. The reason is because the parent would be encrypted under the same key and this key knows how to
     * rederive its own private key bytes from the parent, if needed.
     */
    public isEncrypted(): boolean {
        return this.priv === null && (this.encryptedPrivateKey !== null || !!this.parent?.isEncrypted());
    }

    /**
     * Returns this keys {@link net.bigtangle.crypto.KeyCrypter} <b>or</b> the keycrypter of its parent key.
     */
    public getKeyCrypter(): KeyCrypter | null {
        if (this.keyCrypter) {
            return this.keyCrypter;
        } else if (this.parent) {
            return this.parent.getKeyCrypter();
        } else {
            return null;
        }
    }

    // Ensure sign matches base class signature
    public sign(messageHash: Uint8Array, aesKey?: KeyParameter): ECDSASignature {
        if (this.isEncrypted()) {
            return super.sign(messageHash, aesKey);
        } else {
            const privateKey = this.findOrDerivePrivateKey();
            if (privateKey === null) {
                throw new MissingPrivateKeyException();
            }
            return super.doSign(messageHash, privateKey);
        }
    }

    public decrypt(keyCrypter: KeyCrypter, aesKey: KeyParameter): ECKey {
        return this.decryptDeterministic(keyCrypter, aesKey);
    }
    public decryptDeterministic(keyCrypter: KeyCrypter, aesKey: KeyParameter): DeterministicKey {
        if (this.keyCrypter && !this.keyCrypter.equals(keyCrypter)) {
            throw new Error("The keyCrypter being used to decrypt the key is different to the one that was used to encrypt it");
        }
        const privKey = this.findOrDeriveEncryptedPrivateKey(keyCrypter, aesKey);
        const key = new DeterministicKey(
            this.childNumberPath,
            this.chainCode,
            null,
            privKey,
            this.parent
        );
        if (!key.getPubKeyPoint()?.equals(this.getPubKeyPoint()!)) {
            throw new Error("Provided AES key is wrong");
        }
        if (this.parent === null) {
            key.setCreationTimeSeconds(this.getCreationTimeSeconds());
        }
        return key;
    }

    // For when a key is encrypted, either decrypt our encrypted private key bytes, or work up the tree asking parents
    // to decrypt and re-derive.
    private findOrDeriveEncryptedPrivateKey(keyCrypter: KeyCrypter, aesKey: KeyParameter): BigInteger {
        if (this.encryptedPrivateKey !== null) {
            const decrypted = keyCrypter.decrypt(this.encryptedPrivateKey, aesKey);
            return new BigInteger(DeterministicKey.detBufferToHex(decrypted), 16);
        }
        let cursor: DeterministicKey | null = this.parent;
        while (cursor !== null) {
            if (cursor.encryptedPrivateKey !== null) break;
            cursor = cursor.parent;
        }
        if (cursor === null) {
            throw new Error("Neither this key nor its parents have an encrypted private key");
        }
        const parentalPrivateKeyBytes = keyCrypter.decrypt(cursor.encryptedPrivateKey!, aesKey);
        return this.derivePrivateKeyDownwards(cursor, parentalPrivateKeyBytes);
    }

    private findParentWithPrivKey(): DeterministicKey | null {
        let cursor: DeterministicKey | null = this;
        while (cursor !== null) {
            if (cursor.priv !== null) break;
            cursor = cursor.parent;
        }
        return cursor;
    }

    private findOrDerivePrivateKey(): BigInteger | null {
        const cursor = this.findParentWithPrivKey();
        if (cursor === null) {
            return null;
        }
        return this.derivePrivateKeyDownwards(cursor, DeterministicKey.bigIntegerToBytes(cursor.priv!, 32));
    }

    private derivePrivateKeyDownwards(cursor: DeterministicKey, parentalPrivateKeyBytes: Uint8Array): BigInteger {
        const parentalPrivateKey = new BigInteger(DeterministicKey.detBufferToHex(parentalPrivateKeyBytes), 16);
        const downCursor = new DeterministicKey(
            cursor.childNumberPath,
            cursor.chainCode,
            null,
            parentalPrivateKey,
            cursor.parent
        );
        const path = this.childNumberPath.slice(cursor.getPath().length);
        let currentKey = downCursor;
        for (const num of path) {
            currentKey = HDKeyDerivation.deriveChildKey(currentKey, num);
        }
        if (!currentKey.pub?.equals(this.pub!)) {
            throw new Error("Could not decrypt bytes");
        }
        return currentKey.priv!;
    }

    /**
     * Derives a child at the given index using hardened derivation.
     */
    public derive(child: number): DeterministicKey {
        return HDKeyDerivation.deriveChildKey(this, new ChildNumber(child, true));
    }

    /**
     * Returns the private key of this deterministic key. Even if this object isn't storing the private key,
     * it can be re-derived by walking up to the parents if necessary and this is what will happen.
     * @throws java.lang.IllegalStateException if the parents are encrypted or a watching chain.
     */
    public getPrivKey(): BigInteger {
        const key = this.findOrDerivePrivateKey();
        if (key === null) {
            throw new Error("Private key bytes not available");
        }
        return key;
    }

    public serializePublic(params: NetworkParameters): Uint8Array {
        return this.serialize(params, true);
    }

    public serializePrivate(params: NetworkParameters): Uint8Array {
        return this.serialize(params, false);
    }

    private serialize(params: NetworkParameters, pub: boolean): Uint8Array {
        const buffer = new ArrayBuffer(78);
        const view = new DataView(buffer);
        let offset = 0;
        
        view.setUint32(offset, pub ? params.getBip32HeaderPub() : params.getBip32HeaderPriv(), false); // big-endian
        offset += 4;
        
        view.setUint8(offset, this.depth);
        offset += 1;
        
        view.setUint32(offset, this.getParentFingerprint(), false); // big-endian
        offset += 4;
        
        view.setUint32(offset, this.getChildNumber().getI(), false); // big-endian
        offset += 4;
        
        const chainCodeBytes = this.getChainCode();
        new Uint8Array(buffer, offset, 32).set(chainCodeBytes);
        offset += 32;
        
        const keyBytes = pub ? this.getPubKeyBytes() : this.getPrivKeyBytes33();
        new Uint8Array(buffer, offset, 33).set(keyBytes);
        offset += 33;

        if (offset !== 78) {
            throw new Error("Serialization error: buffer position is not 78");
        }
        
        return new Uint8Array(buffer);
    }

    public serializePubB58(params: NetworkParameters): string {
        return DeterministicKey.toBase58(this.serialize(params, true));
    }

    public serializePrivB58(params: NetworkParameters): string {
        return DeterministicKey.toBase58(this.serialize(params, false));
    }

    static toBase58(data: Uint8Array): string {
        return Base58.encode(DeterministicKey.addChecksum(data));
    }

    /** Deserialize a base-58-encoded HD Key with no parent */
    public static deserializeB58(base58: string, params: NetworkParameters): DeterministicKey;
    /**
      * Deserialize a base-58-encoded HD Key.
      *  @param parent The parent node in the given key's deterministic hierarchy.
      *  @throws IllegalArgumentException if the base58 encoded key could not be parsed.
      */
    public static deserializeB58(parent: DeterministicKey | null, base58: string, params: NetworkParameters): DeterministicKey;
    public static deserializeB58(...args: any[]): DeterministicKey {
        let parent: DeterministicKey | null = null;
        let base58: string;
        let params: NetworkParameters;

        if (args.length === 2) {
            [base58, params] = args;
        } else if (args.length === 3) {
            [parent, base58, params] = args;
        } else {
            throw new Error("Invalid number of arguments");
        }

        const decoded = Base58.decodeChecked(base58);
        return DeterministicKey.deserialize(params, decoded, parent);
    }

    /**
      * Deserialize an HD Key with no parent
      */
    public static deserialize(params: NetworkParameters, serializedKey: Uint8Array): DeterministicKey;
    /**
      * Deserialize an HD Key.
     * @param parent The parent node in the given key's deterministic hierarchy.
     */
    public static deserialize(params: NetworkParameters, serializedKey: Uint8Array, parent: DeterministicKey | null): DeterministicKey;
    public static deserialize(...args: any[]): DeterministicKey {
        let params: NetworkParameters;
        let serializedKey: Uint8Array;
        let parent: DeterministicKey | null = null;

        if (args.length === 2) {
            [params, serializedKey] = args;
        } else if (args.length === 3) {
            [params, serializedKey, parent] = args;
        } else {
            throw new Error("Invalid number of arguments");
        }

        const buffer = serializedKey.buffer;
        const view = new DataView(buffer, serializedKey.byteOffset, serializedKey.byteLength);
        let offset = 0;
        
        const header = view.getUint32(offset, false); // big-endian
        offset += 4;
        
        const pub = header === params.getBip32HeaderPub();
        
        const depth = view.getUint8(offset);
        offset += 1;
        
        const parentFingerprint = view.getUint32(offset, false); // big-endian
        offset += 4;
        
        const i = view.getUint32(offset, false); // big-endian
        const childNumber = new ChildNumber(i);
        offset += 4;
        
        let path: ChildNumber[];
        if (parent !== null) {
            if (parentFingerprint === 0) {
                throw new Error("Parent was provided but this key doesn't have one");
            }
            if (parent.getFingerprint() !== parentFingerprint) {
                throw new Error("Parent fingerprints don't match");
            }
            path = HDUtils.append(parent.getPath(), childNumber);
            if (path.length !== depth) {
                throw new Error("Depth does not match");
            }
        } else {
            if (depth >= 1) {
                // We have been given a key that is not a root key, yet we lack the object representing the parent.
                // This can happen when deserializing an account key for a watching wallet.  In this case, we assume that
                // the client wants to conceal the key's position in the hierarchy.  The path is truncated at the
                // parent's node.
                path = [childNumber];
            } else {
                path = [];
            }
        }
        const chainCode = new Uint8Array(buffer, serializedKey.byteOffset + offset, 32);
        offset += 32;
        const keyData = new Uint8Array(buffer, serializedKey.byteOffset + offset, 33);
        offset += 33;

        if (offset !== serializedKey.byteLength) {
            throw new Error("Found unexpected data in key");
        }

        if (pub) {
            return new DeterministicKey(path, chainCode, ECPoint.decodePoint(keyData), null, parent, depth, parentFingerprint);
        } else {
            // Convert keyData to BigInteger
            const privBI = new BigInteger(Buffer.from(keyData).toString('hex'), 16);
            return new DeterministicKey(path, chainCode, null, privBI, parent, depth, parentFingerprint);
        }
    }

    /**
     * The creation time of a deterministic key is equal to that of its parent, unless this key is the root of a tree
     * in which case the time is stored alongside the key as per normal, see {@link net.bigtangle.core.ECKey#getCreationTimeSeconds()}.
     */
    public getCreationTimeSeconds(): number {
        if (this.parent !== null) {
            return this.parent.getCreationTimeSeconds();
        } else {
            return super.getCreationTimeSeconds();
        }
    }

    /**
     * The creation time of a deterministic key is equal to that of its parent, unless this key is the root of a tree.
     * Thus, setting the creation time on a leaf is forbidden.
     */
    public setCreationTimeSeconds(newCreationTimeSeconds: number): void {
        if (this.parent !== null) {
            throw new Error("Creation time can only be set on root keys.");
        } else {
            super.setCreationTimeSeconds(newCreationTimeSeconds);
        }
    }

    /**
     * Verifies equality of all fields but NOT the parent pointer (thus the same key derived in two separate heirarchy
     * objects will equal each other.
     */
    public equals(o: any): boolean {
        if (this === o) return true;
        if (!(o instanceof DeterministicKey)) return false;
        return super.equals(o) &&
            DeterministicKey.bytesEqual(this.chainCode, o.chainCode) &&
            this.childNumberPath.length === o.childNumberPath.length &&
            this.childNumberPath.every((cn, i) => cn.equals(o.childNumberPath[i]));
    }

    public hashCode(): number {
        let result = super.hashCode();
        result = 31 * result + this.chainCode.reduce((acc, byte) => acc + byte, 0);
        result = 31 * result + this.childNumberPath.reduce((acc, cn) => acc + cn.hashCode(), 0);
        return result;
    }

    public toString(): string {
        let s = `DeterministicKey{pub=${DeterministicKey.detBufferToHex(this.getPubKeyBytes())}, ` +
                `chainCode=${DeterministicKey.detBufferToHex(this.chainCode)}, path=${this.getPathAsString()}`;
        if (this.creationTimeSeconds > 0) {
            s += `, creationTimeSeconds=${this.creationTimeSeconds}`;
        }
        s += `, isEncrypted=${this.isEncrypted()}, isPubKeyOnly=${this.isPubKeyOnly()}}`;
        return s;
    }

    public formatKeyWithAddress(
        includePrivateKeys: boolean,
        builder: string[], // Changed from StringBuilder to string[]
        params: NetworkParameters
    ): void {
        // Assuming toAddress and getPubKeyHash are available in ECKey or Utils
        // const address = this.toAddress(params);
        // builder.push(`  addr:${address}`);
        // builder.push(`  hash160:${Utils.HEX.encode(this.getPubKeyHash())}`);
        builder.push(`  (${this.getPathAsString()})`);
        if (includePrivateKeys) {
            builder.push(`  ${this.toStringWithPrivate(params)}`);
        }
    }
    
    public toStringWithPrivate(params: NetworkParameters): string {
        // This method needs to be implemented based on how you want to represent the private key
        // For now, returning a placeholder
        return "Private key not available";
    }
}