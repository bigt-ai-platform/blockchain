import { BigInteger } from '../core/BigInteger';
import { ECKey } from '../ECKey';
import { secp256k1 } from '@noble/curves/secp256k1';
import { sha256 } from '@noble/hashes/sha256';
import { hmac } from '@noble/hashes/hmac';
import { aes, utils } from '@noble/ciphers/aes';
import { randomBytes } from '@noble/hashes/utils';
import { ECPoint } from '../ECPoint';
import { Utils } from '../utils/Utils';
import { ConcatKDFBytesGenerator, KDFParameters, SHA256Digest } from '../crypto/ConcatKDFBytesGenerator';

export class ECIESCoder {
    public static readonly KEY_SIZE = 128; // bits

    public static async decrypt(privKey: BigInteger, cipher: Uint8Array, macData: Uint8Array | null = null): Promise<Uint8Array> {
        const ephemBytesLength = 2 * ((ECKey.CURVE.getCurve().getFieldSize() + 7) / 8) + 1;
        const ephemBytes = cipher.slice(0, ephemBytesLength);
        const IV = cipher.slice(ephemBytesLength, ephemBytesLength + (ECIESCoder.KEY_SIZE / 8));
        const cipherBody = cipher.slice(ephemBytesLength + (ECIESCoder.KEY_SIZE / 8));

        const ephemPoint = ECPoint.decodePoint(ephemBytes);

        return ECIESCoder.decryptWithEphemeral(ephemPoint, privKey, IV, cipherBody, macData);
    }

    public static async decryptWithEphemeral(ephem: ECPoint, prv: BigInteger, IV: Uint8Array, cipher: Uint8Array, macData: Uint8Array | null): Promise<Uint8Array> {
        const sharedSecret = secp256k1.getSharedSecret(prv.toByteArray(), ephem.encode(false));

        const kdf = ConcatKDFBytesGenerator.create(new SHA256Digest());
        kdf.init(new KDFParameters(sharedSecret, null));
        const derivedKeys = new Uint8Array(ECIESCoder.KEY_SIZE / 8 * 2);
        kdf.generateBytes(derivedKeys, 0, derivedKeys.length);

        const encryptionKey = derivedKeys.slice(0, ECIESCoder.KEY_SIZE / 8);
        const macKey = derivedKeys.slice(ECIESCoder.KEY_SIZE / 8);

        // Verify MAC
        const expectedMac = hmac(sha256, macKey, new Uint8Array([...IV, ...cipher.slice(0, cipher.length - 32), ...(macData || [])]));
        const actualMac = cipher.slice(cipher.length - 32); // Assuming 32-byte SHA256 HMAC
        const ciphertextWithoutMac = cipher.slice(0, cipher.length - 32);

        if (!Utils.bytesEqual(expectedMac, actualMac)) {
            throw new Error("MAC mismatch");
        }

        // Decrypt
        const aesCtr = aes.CTR(encryptionKey, IV);
        const plaintext = aesCtr.decrypt(ciphertextWithoutMac);

        return plaintext;
    }

    public static async encrypt(toPub: ECPoint, plaintext: Uint8Array, macData: Uint8Array | null = null): Promise<Uint8Array> {
        const ephemeralKeyPair = secp256k1.utils.randomPrivateKey();
        const ephemeralPublicKey = secp256k1.getPublicKey(ephemeralKeyPair);
        const ephemeralPrivateKey = BigInt('0x' + Utils.HEX.encode(ephemeralKeyPair));

        const IV = randomBytes(ECIESCoder.KEY_SIZE / 8);

        const sharedSecret = secp256k1.getSharedSecret(ephemeralKeyPair, toPub.encode(false));

        const kdf = ConcatKDFBytesGenerator.create(new SHA256Digest());
        kdf.init(new KDFParameters(sharedSecret, null));
        const derivedKeys = new Uint8Array(ECIESCoder.KEY_SIZE / 8 * 2);
        kdf.generateBytes(derivedKeys, 0, derivedKeys.length);

        const encryptionKey = derivedKeys.slice(0, ECIESCoder.KEY_SIZE / 8);
        const macKey = derivedKeys.slice(ECIESCoder.KEY_SIZE / 8);

        // Encrypt
        const aesCtr = aes.CTR(encryptionKey, IV);
        const encrypted = aesCtr.encrypt(plaintext);

        // Calculate MAC
        const mac = hmac(sha256, macKey, new Uint8Array([...IV, ...encrypted, ...(macData || [])]));

        const output = new Uint8Array(ephemeralPublicKey.length + IV.length + encrypted.length + mac.length);
        let offset = 0;
        output.set(ephemeralPublicKey, offset);
        offset += ephemeralPublicKey.length;
        output.set(IV, offset);
        offset += IV.length;
        output.set(encrypted, offset);
        offset += encrypted.length;
        output.set(mac, offset);

        return output;
    }

    public static getOverhead(): number {
        // 65 bytes for uncompressed ephemeral public key + 16 bytes for IV + 32 bytes for SHA256 MAC
        return 65 + (ECIESCoder.KEY_SIZE / 8) + 32;
    }
}
