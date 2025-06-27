
import { Buffer } from 'buffer';
import * as crypto from 'crypto';

describe('Test', () => {
    const ALGORITHM = 'RSA-KEM';

    function generateKeyPair(): crypto.RsaKeyPairKeyObjectOptions {
        const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
            modulusLength: 512,
        });
        return { publicKey, privateKey };
    }

    function encrypt(publicKey: crypto.KeyObject, inputData: Buffer): Buffer {
        return crypto.publicEncrypt(publicKey, inputData);
    }

    function decrypt(privateKey: crypto.KeyObject, inputData: Buffer): Buffer {
        return crypto.privateDecrypt(privateKey, inputData);
    }

    test('main', () => {
        const { publicKey, privateKey } = generateKeyPair();

        const encryptedData = encrypt(
            publicKey,
            Buffer.from('hi this is Visruth here'),
        );

        const decryptedData = decrypt(privateKey, encryptedData);

        expect(decryptedData.toString()).toBe('hi this is Visruth here');
    });
});
