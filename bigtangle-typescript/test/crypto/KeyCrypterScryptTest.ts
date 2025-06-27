
import { Buffer } from 'buffer';
import { KeyCrypterScrypt } from '../../src/net/bigtangle/crypto/KeyCrypterScrypt';
import { EncryptedData } from '../../src/net/bigtangle/crypto/EncryptedData';
import { Utils } from '../../src/net/bigtangle/utils/Utils';
import { KeyCrypterException } from '../../src/net/bigtangle/crypto/KeyCrypterException';

describe('KeyCrypterScryptTest', () => {
    const TEST_BYTES1 = Buffer.from([
        0, -101, 2, 103, -4, 105, 6, 107, 8, -109, 10, 111, -12, 113, 14, -115,
        16, 117, -18, 119, 20, 121, 22, 123, -24, 125, 26, 127, -28, 29, -30, 31,
    ]);

    const PASSWORD1 = 'aTestPassword';
    const PASSWORD2 = '0123456789';
    const WRONG_PASSWORD = 'thisIsTheWrongPassword';

    let keyCrypter: KeyCrypterScrypt;

    beforeEach(() => {
        keyCrypter = new KeyCrypterScrypt();
    });

    test('testKeyCrypterGood1', () => {
        const data = keyCrypter.encrypt(
            TEST_BYTES1,
            keyCrypter.deriveKey(PASSWORD1),
        );
        expect(data).not.toBeNull();

        const reborn = keyCrypter.decrypt(data, keyCrypter.deriveKey(PASSWORD1));
        expect(Utils.HEX.encode(reborn)).toBe(Utils.HEX.encode(TEST_BYTES1));
    });

    test('testKeyCrypterGood2', () => {
        const numberOfTests = 16;
        for (let i = 0; i < numberOfTests; i++) {
            const plainText =
                Math.random().toString(36).substring(2, 15) +
                Math.random().toString(36).substring(2, 15);
            const password =
                Math.random().toString(36).substring(2, 15) +
                Math.random().toString(36).substring(2, 15);

            const data = keyCrypter.encrypt(
                Buffer.from(plainText),
                keyCrypter.deriveKey(password),
            );
            expect(data).not.toBeNull();

            const reconstructedPlainBytes = keyCrypter.decrypt(
                data,
                keyCrypter.deriveKey(password),
            );
            expect(Utils.HEX.encode(reconstructedPlainBytes)).toBe(
                Utils.HEX.encode(Buffer.from(plainText)),
            );
        }
    });

    test('testKeyCrypterWrongPassword', () => {
        let builder = '';
        for (let i = 0; i < 100; i++) {
            builder += i + ' The quick brown fox';
        }

        const data = keyCrypter.encrypt(
            Buffer.from(builder),
            keyCrypter.deriveKey(PASSWORD2),
        );
        expect(data).not.toBeNull();

        try {
            keyCrypter.decrypt(data, keyCrypter.deriveKey(WRONG_PASSWORD));
            fail('Decrypt with wrong password did not throw exception');
        } catch (e) {
            expect(e).toBeInstanceOf(KeyCrypterException);
            expect(e.message).toContain('Could not decrypt');
        }
    });

    test('testEncryptDecryptBytes1', () => {
        const data = keyCrypter.encrypt(
            TEST_BYTES1,
            keyCrypter.deriveKey(PASSWORD1),
        );
        expect(data).not.toBeNull();

        const rebornPlainBytes = keyCrypter.decrypt(
            data,
            keyCrypter.deriveKey(PASSWORD1),
        );
        expect(Utils.HEX.encode(rebornPlainBytes)).toBe(
            Utils.HEX.encode(TEST_BYTES1),
        );
    });

    test('testEncryptDecryptBytes2', () => {
        for (let i = 0; i < 50; i++) {
            const plainBytes = Buffer.alloc(i);
            for (let j = 0; j < i; j++) {
                plainBytes[j] = Math.floor(Math.random() * 256);
            }

            const data = keyCrypter.encrypt(
                plainBytes,
                keyCrypter.deriveKey(PASSWORD1),
            );
            expect(data).not.toBeNull();

            const rebornPlainBytes = keyCrypter.decrypt(
                data,
                keyCrypter.deriveKey(PASSWORD1),
            );
            expect(Utils.HEX.encode(rebornPlainBytes)).toBe(
                Utils.HEX.encode(plainBytes),
            );
        }
    });
});
