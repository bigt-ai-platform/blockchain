import { Buffer } from 'buffer';
import { ECKey } from '../../src/net/bigtangle/core/ECKey';
import { Sha256Hash } from '../../src/net/bigtangle/core/Sha256Hash';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { DumpedPrivateKey } from '../../src/net/bigtangle/utils/DumpedPrivateKey';
import { Utils } from '../../src/net/bigtangle/utils/Utils';
import { Address } from '../../src/net/bigtangle/core/Address';
import { KeyCrypterScrypt } from '../../src/net/bigtangle/crypto/KeyCrypterScrypt';
import { KeyCrypter } from '../../src/net/bigtangle/crypto/KeyCrypter';
import { EncryptedData } from '../../src/net/bigtangle/crypto/EncryptedData';
import { Transaction } from '../../src/net/bigtangle/core/Transaction';
import { TransactionSignature } from '../../src/net/bigtangle/crypto/TransactionSignature';
import { BigInteger } from '../../src/net/bigtangle/core/BigInteger';

describe('ECKeyTest', () => {
    const PASSWORD1 = 'my hovercraft has eels';
    const WRONG_PASSWORD = 'it is a snowy day today';
    let keyCrypter: KeyCrypter;

    beforeEach(() => {
        keyCrypter = new KeyCrypterScrypt();
    });

    test('sValue', () => {
        const key = new ECKey();
        for (let i = 0; i < 10; i++) {
            const hash = Sha256Hash.of(Buffer.from([i]));
            const sig = key.sign(hash);
            expect(sig.isCanonical()).toBe(true);
        }
    });

    test('testSignatures', () => {
        const privkey = BigInt(
            '0x180cb41c7c600be951b5d3d0a7334acc7506173875834f7a6c4c786a28fcbb19',
        );
        const key = ECKey.fromPrivate(privkey);
        const output = key.sign(Sha256Hash.ZERO_HASH).encodeToDER();
        expect(key.verify(Sha256Hash.ZERO_HASH.getBytes(), output)).toBe(true);
    });

    test.skip('base58Encoding', () => {
        const addr = 'mqAJmaxMcG5pPHHc3H3NtyXzY7kGbJLuMF';
        const privkey = '92shANodC6Y4evT5kFzjNFQAdjqTtHAnDTLzqBBq4BbKUPyx6CD';
        const key = DumpedPrivateKey.fromBase58(
            MainNetParams.get(),
            privkey,
        ).getKey();
        expect(key.getPrivateKeyEncoded(MainNetParams.get()).toString()).toBe(
            privkey,
        );
        expect(key.toAddress(MainNetParams.get()).toString()).toBe(addr);
    });

    test.skip('base58Encoding_leadingZero', () => {
        const privkey = '91axuYLa8xK796DnBXXsMbjuc8pDYxYgJyQMvFzrZ6UfXaGYuqL';
        const key = DumpedPrivateKey.fromBase58(
            MainNetParams.get(),
            privkey,
        ).getKey();
        expect(key.getPrivateKeyEncoded(MainNetParams.get()).toString()).toBe(
            privkey,
        );
        expect(key.getPrivKeyBytes()[0]).toBe(0);
    });

    test('base58Encoding_stress', () => {
        for (let i = 0; i < 20; i++) {
            const key = new ECKey();
            const key1 = DumpedPrivateKey.fromBase58(
                MainNetParams.get(),
                key.getPrivateKeyEncoded(MainNetParams.get()).toString(),
            ).getKey();
            expect(Utils.HEX.encode(key.getPrivKeyBytes())).toBe(
                Utils.HEX.encode(key1.getPrivKeyBytes()),
            );
        }
    });

    test('signTextMessage', () => {
        const key = new ECKey();
        const message = '聡中本';
        const signatureBase64 = key.signMessage(message);
        key.verifyMessage(message, signatureBase64);
        try {
            key.verifyMessage('Evil attacker says hello!', signatureBase64);
            fail();
        } catch (e) {
            // OK.
        }
    });

    test('verifyMessage', () => {
        const message = 'hello';
        const sigBase64 =
            'HxNZdo6ggZ41hd3mM3gfJRqOQPZYcO8z8qdX2BwmpbF11CaOQV+QiZGGQxaYOncKoNW61oRuSMMF8udfK54XqI8=';
        const expectedAddress = Address.fromBase58(
            MainNetParams.get(),
            '14YPSNPi6NSXnUxtPAsyJSuw3pv7AU3Cag',
        );
        const key = ECKey.signedMessageToKey(message, sigBase64);
        const gotAddress = key.toAddress(MainNetParams.get());
        expect(gotAddress).toEqual(expectedAddress);
    });

    test('keyRecovery', () => {
        const key = new ECKey();
        const message = 'Hello World!';
        const hash = Sha256Hash.of(Buffer.from(message));
        const sig = key.sign(hash);
        const pubKeyOnly = ECKey.fromPublic(key.getPubKeyPoint());
        let found = false;
        for (let i = 0; i < 4; i++) {
            const key2 = ECKey.recoverFromSignature(i, sig, hash, true);
            if (pubKeyOnly.equals(key2)) {
                found = true;
                break;
            }
        }
        expect(found).toBe(true);
    });

    test('testUnencryptedCreate', () => {
        Utils.setMockClock();
        const key = new ECKey();
        const time = key.getCreationTimeSeconds();
        expect(time).not.toBe(0);
        expect(key.isEncrypted()).toBe(false);
        const originalPrivateKeyBytes = key.getPrivKeyBytes();
        const encryptedKey = key.encrypt(keyCrypter, keyCrypter.deriveKey(PASSWORD1));
        expect(encryptedKey.getCreationTimeSeconds()).toBe(time);
        expect(encryptedKey.isEncrypted()).toBe(true);
        expect(encryptedKey.getSecretBytes()).toBeNull();
        const decryptedKey = encryptedKey.decrypt(keyCrypter.deriveKey(PASSWORD1));
        expect(decryptedKey.isEncrypted()).toBe(false);
        expect(
            Buffer.compare(originalPrivateKeyBytes, decryptedKey.getPrivKeyBytes()),
        ).toBe(0);
    });

    test('testEncryptedCreate', () => {
        const unencryptedKey = new ECKey();
        const originalPrivateKeyBytes = unencryptedKey.getPrivKeyBytes();
        const encryptedPrivateKey = keyCrypter.encrypt(
            unencryptedKey.getPrivKeyBytes(),
            keyCrypter.deriveKey(PASSWORD1),
        );
        const encryptedKey = ECKey.fromEncrypted(
            encryptedPrivateKey,
            keyCrypter,
            unencryptedKey.getPubKey(),
        );
        expect(encryptedKey.isEncrypted()).toBe(true);
        expect(encryptedKey.getSecretBytes()).toBeNull();
        const rebornUnencryptedKey = encryptedKey.decrypt(
            keyCrypter.deriveKey(PASSWORD1),
        );
        expect(rebornUnencryptedKey.isEncrypted()).toBe(false);
        expect(
            Buffer.compare(
                originalPrivateKeyBytes,
                rebornUnencryptedKey.getPrivKeyBytes(),
            ),
        ).toBe(0);
    });

    test('testEncryptionIsReversible', () => {
        const originalUnencryptedKey = new ECKey();
        const encryptedPrivateKey = keyCrypter.encrypt(
            originalUnencryptedKey.getPrivKeyBytes(),
            keyCrypter.deriveKey(PASSWORD1),
        );
        const encryptedKey = ECKey.fromEncrypted(
            encryptedPrivateKey,
            keyCrypter,
            originalUnencryptedKey.getPubKey(),
        );

        expect(encryptedKey.isEncrypted()).toBe(true);

        expect(
            ECKey.encryptionIsReversible(
                originalUnencryptedKey,
                encryptedKey,
                keyCrypter,
                keyCrypter.deriveKey(PASSWORD1),
            ),
        ).toBe(true);

        expect(
            ECKey.encryptionIsReversible(
                originalUnencryptedKey,
                encryptedKey,
                keyCrypter,
                keyCrypter.deriveKey(WRONG_PASSWORD),
            ),
        ).toBe(false);

        const goodEncryptedPrivateKeyBytes = encryptedPrivateKey.encryptedBytes;
        const badEncryptedPrivateKeyBytes = Buffer.alloc(
            goodEncryptedPrivateKeyBytes.length,
        );
        const badEncryptedPrivateKey = new EncryptedData(
            encryptedPrivateKey.initialisationVector,
            badEncryptedPrivateKeyBytes,
        );
        const badEncryptedKey = ECKey.fromEncrypted(
            badEncryptedPrivateKey,
            keyCrypter,
            originalUnencryptedKey.getPubKey(),
        );
        expect(
            ECKey.encryptionIsReversible(
                originalUnencryptedKey,
                badEncryptedKey,
                keyCrypter,
                keyCrypter.deriveKey(PASSWORD1),
            ),
        ).toBe(false);
    });

    test('testToString', () => {
        const key = ECKey.fromPrivate(BigInt(10)).decompress();
        const params = MainNetParams.get();
        expect(key.toString()).toBe(
            'ECKey{pub HEX=04a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7893aba425419bc27a3b6c7e693a24c696f794c2ed877a1593cbee53b037368d7, isEncrypted=false, isPubKeyOnly=false}',
        );
        expect(key.toStringWithPrivate(params)).toBe(
            'ECKey{pub HEX=04a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7893aba425419bc27a3b6c7e693a24c696f794c2ed877a1593cbee53b037368d7, priv HEX=000000000000000000000000000000000000000000000000000000000000000a, priv WIF=5HpHagT65TZzG1PH3CSu63k8DbpvD8s5ip4nEB3kEsreBoNWTw6, isEncrypted=false, isPubKeyOnly=false}',
        );
    });

    test('testGetPrivateKeyAsHex', () => {
        const key = ECKey.fromPrivate(BigInt(10)).decompress();
        expect(key.getPrivateKeyAsHex()).toBe(
            '000000000000000000000000000000000000000000000000000000000000000a',
        );
    });

    test('testGetPublicKeyAsHex', () => {
        const key = ECKey.fromPrivate(BigInt(10)).decompress();
        expect(key.getPublicKeyAsHex()).toBe(
            '04a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7893aba425419bc27a3b6c7e693a24c696f794c2ed877a1593cbee53b037368d7',
        );
    });

    test('keyRecoveryWithEncryptedKey', () => {
        const unencryptedKey = new ECKey();
        const aesKey = keyCrypter.deriveKey(PASSWORD1);
        const encryptedKey = unencryptedKey.encrypt(keyCrypter, aesKey);

        const message = 'Goodbye Jupiter!';
        const hash = Sha256Hash.of(Buffer.from(message));
        const sig = encryptedKey.sign(hash, aesKey);
        const pubKeyOnly = ECKey.fromPublic(unencryptedKey.getPubKeyPoint());
        let found = false;
        for (let i = 0; i < 4; i++) {
            const key2 = ECKey.recoverFromSignature(i, sig, hash, true);
            if (pubKeyOnly.equals(key2)) {
                found = true;
                break;
            }
        }
        expect(found).toBe(true);
    });

    test('roundTripDumpedPrivKey', () => {
        const key = new ECKey();
        expect(key.isCompressed()).toBe(true);
        const params = MainNetParams.get();
        const base58 = key.getPrivateKeyEncoded(params).toString();
        const key2 = DumpedPrivateKey.fromBase58(params, base58).getKey();
        expect(key2.isCompressed()).toBe(true);
        expect(Buffer.compare(key.getPrivKeyBytes(), key2.getPrivKeyBytes())).toBe(
            0,
        );
        expect(Buffer.compare(key.getPubKey(), key2.getPubKey())).toBe(0);
    });

    test('testCreatedSigAndPubkeyAreCanonical', () => {
        const key = new ECKey();
        if (!ECKey.isPubKeyCanonical(key.getPubKey())) {
            fail();
        }

        const hash = Buffer.alloc(32);
        for (let i = 0; i < hash.length; i++) {
            hash[i] = Math.floor(Math.random() * 256);
        }
        const sigBytes = key.sign(Sha256Hash.wrap(hash)).encodeToDER();
        const encodedSig = Buffer.concat([
            sigBytes,
            Buffer.from([Transaction.SigHash.ALL]),
        ]);
        if (!TransactionSignature.isEncodingCanonical(encodedSig)) {
            fail();
        }
    });
});
