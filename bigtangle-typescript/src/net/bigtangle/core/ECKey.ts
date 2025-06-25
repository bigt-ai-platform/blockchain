import { createSign, createVerify, generateKeyPairSync, createPrivateKey, createPublicKey, KeyObject } from 'crypto';
import { Buffer } from 'buffer';
import { Sha256Hash } from './Sha256Hash';
import { Utils } from './Utils';
import { NetworkParameters } from './NetworkParameters';
import { Address } from './Address';

export class ECKey {
    private privateKey: KeyObject | null;
    private publicKey: KeyObject | null;
    private compressed: boolean;

    constructor() {
        this.privateKey = null;
        this.publicKey = null;
        this.compressed = true;
    }

    public static fromPrivateKey(privateKeyBytes: Uint8Array, compressed: boolean = true): ECKey {
        const key = new ECKey();
        key.privateKey = createPrivateKey({
            key: Buffer.from(privateKeyBytes),
            format: 'der',
            type: 'pkcs8'
        });
        key.compressed = compressed;
        key.publicKey = derivePublicKey(key.privateKey, compressed);
        return key;
    }

    public static fromPublicKey(publicKeyBytes: Uint8Array, compressed: boolean = true): ECKey {
        const key = new ECKey();
        key.publicKey = createPublicKey({
            key: Buffer.from(publicKeyBytes),
            format: 'der',
            type: 'spki'
        });
        key.compressed = compressed;
        return key;
    }

    public static createNew(compressed: boolean = true): ECKey {
        const key = new ECKey();
        const { privateKey, publicKey } = generateKeyPairSync('ec', {
            namedCurve: 'secp256k1',
        });
        key.privateKey = privateKey;
        key.publicKey = publicKey;
        key.compressed = compressed;
        return key;
    }

    public sign(hash: Sha256Hash): Uint8Array {
        if (!this.privateKey) {
            throw new Error('Private key not available for signing');
        }
        const signer = createSign('SHA256');
        signer.update(hash.toBuffer());
        return signer.sign(this.privateKey);
    }

    public verify(hash: Sha256Hash, signature: Uint8Array): boolean {
        if (!this.publicKey) {
            throw new Error('Public key not available for verification');
        }
        const verifier = createVerify('SHA256');
        verifier.update(hash.toBuffer());
        return verifier.verify(this.publicKey, signature);
    }

    public toAddress(params: NetworkParameters): Address {
        if (!this.publicKey) {
            throw new Error('Public key not available');
        }
        const pubKeyBytes = this.getPubKeyHash();
        return new Address(params, params.getAddressHeader(), pubKeyBytes);
    }

    public getPrivateKeyBytes(): Uint8Array {
        if (!this.privateKey) {
            throw new Error('Private key not available');
        }
        return this.privateKey.export({ format: 'der', type: 'pkcs8' });
    }

    public getPubKeyHash(): Uint8Array {
        if (!this.publicKey) {
            throw new Error('Public key not available');
        }
        const pubKeyBytes = this.publicKey.export({ format: 'der', type: 'spki' });
        return Sha256Hash.hash(pubKeyBytes).toBuffer();
    }

    public isCompressed(): boolean {
        return this.compressed;
    }
}

function derivePublicKey(privateKey: KeyObject, compressed: boolean): KeyObject {
    return createPublicKey(privateKey);
}
