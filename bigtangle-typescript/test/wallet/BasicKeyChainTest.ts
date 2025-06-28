import { Buffer } from 'buffer';
import { BasicKeyChain } from '../../src/net/bigtangle/wallet/BasicKeyChain';
import { ECKey } from '../../src/net/bigtangle/core/ECKey';
import { KeyChain } from '../../src/net/bigtangle/wallet/KeyChain';
import { KeyCrypterException } from '../../src/net/bigtangle/crypto/KeyCrypterException';
import { KeyCrypterScrypt } from '../../src/net/bigtangle/crypto/KeyCrypterScrypt';
import { UnreadableWalletException } from '../../src/net/bigtangle/wallet/UnreadableWalletException';
import { BloomFilter } from '../../src/net/bigtangle/core/BloomFilter';
import { Utils } from '../../src/net/bigtangle/utils/Utils';
import { BigInteger } from '../../src/net/bigtangle/core/BigInteger';

describe('BasicKeyChainTest', () => {
    let chain: BasicKeyChain;

    beforeEach(() => {
        chain = new BasicKeyChain();
    });

    test('importKeys', () => {
        const now = Utils.currentTimeSeconds();
        Utils.setMockClock();
        const key1 = ECKey.fromPrivate(new BigInteger('1'));
        Utils.rollMockClock(86400);
        const key2 = ECKey.fromPrivate(new BigInteger('1'));
        const keys = [key1, key2];

        expect(chain.importKeys(keys)).toBe(2);
        expect(chain.numKeys()).toBe(2);
        expect(chain.getEarliestKeyCreationTime()).toBe(now);

        const newKey = ECKey.fromPrivate(new BigInteger('1'));
        keys.push(newKey);
        expect(chain.importKeys(keys)).toBe(1);
        expect(chain.importKeys(keys)).toBe(0);

        expect(chain.hasKey(key1)).toBe(true);
        expect(chain.hasKey(key2)).toBe(true);
        expect(chain.findKeyFromPubHash(key1.getPubKeyHash())).toEqual(key1);
        expect(chain.findKeyFromPubKey(key2.getPubKey())).toEqual(key2);
        expect(chain.findKeyFromPubKey(key2.getPubKeyHash())).toBeNull();
    });

    test('removeKey', () => {
        const key = ECKey.fromPrivate(new BigInteger('1'));
        chain.importKeys(key);
        expect(chain.numKeys()).toBe(1);
        expect(chain.removeKey(key)).toBe(true);
        expect(chain.numKeys()).toBe(0);
        expect(chain.removeKey(key)).toBe(false);
    });

    test('checkPasswordNoKeys', () => {
        expect(() => {
            chain.checkPassword('test');
        }).toThrow();
    });

    test('checkPasswordNotEncrypted', () => {
        expect(() => {
            const keys = [ECKey.fromPrivate(new BigInteger('1')), ECKey.fromPrivate(new BigInteger('1'))];
            chain.importKeys(keys);
            chain.checkPassword('test');
        }).toThrow();
    });

    test('doubleEncryptFails', () => {
        expect(() => {
            const keys = [ECKey.fromPrivate(new BigInteger('1')), ECKey.fromPrivate(new BigInteger('1'))];
            chain.importKeys(keys);
            chain = chain.toEncrypted('foo');
            chain.toEncrypted('foo');
        }).toThrow();
    });

    test('encryptDecrypt', () => {
        const key1 = ECKey.fromPrivate(new BigInteger('1'));
        chain.importKeys(key1, new ECKey());
        const PASSWORD = 'foobar';
        chain = chain.toEncrypted(PASSWORD);
        const keyCrypter = chain.getKeyCrypter();
        expect(keyCrypter).not.toBeNull();
        expect(keyCrypter instanceof KeyCrypterScrypt).toBe(true);

        expect(chain.checkPassword(PASSWORD)).toBe(true);
        expect(chain.checkPassword('wrong')).toBe(false);
        const key = chain.findKeyFromPubKey(key1.getPubKey());
        expect(key.isEncrypted()).toBe(true);
        expect(key.isPubKeyOnly()).toBe(true);
        expect(key.isWatching()).toBe(false);
        expect(key.getSecretBytes()).toBeNull();

        expect(() => {
            chain.importKeys(new ECKey());
        }).toThrow(KeyCrypterException);

        expect(() => {
            chain.toDecrypted(keyCrypter.deriveKey('wrong'));
        }).toThrow(KeyCrypterException);

        chain = chain.toDecrypted(PASSWORD);
        const decryptedKey = chain.findKeyFromPubKey(key1.getPubKey());
        expect(decryptedKey.isEncrypted()).toBe(false);
        expect(decryptedKey.isPubKeyOnly()).toBe(false);
        expect(decryptedKey.isWatching()).toBe(false);
        decryptedKey.getPrivKeyBytes();
    });

    test('cannotImportEncryptedKey', () => {
        expect(() => {
            const key1 = new ECKey();
            chain.importKeys([key1]);
            chain = chain.toEncrypted('foobar');
            const encryptedKey = chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
            expect(encryptedKey.isEncrypted()).toBe(true);

            const chain2 = new BasicKeyChain();
            chain2.importKeys([encryptedKey]);
        }).toThrow(KeyCrypterException);
    });

    test('cannotMixParams', () => {
        expect(() => {
            chain = chain.toEncrypted('foobar');
            const scrypter = new KeyCrypterScrypt(2);
            const key1 = new ECKey().encrypt(
                scrypter,
                scrypter.deriveKey('other stuff'),
            );
            chain.importKeys(key1);
        }).toThrow(KeyCrypterException);
    });

    test('watching', () => {
        const key1 = new ECKey();
        const pub = ECKey.fromPublicOnly(key1.getPubKeyPoint());
        chain.importKeys(pub);
        expect(chain.numKeys()).toBe(1);
        expect(chain.findKeyFromPubKey(pub.getPubKey()).hasPrivKey()).toBe(false);
    });

    test('bloom', () => {
        const key1 = new ECKey();
        const key2 = new ECKey();
        chain.importKeys(key1, key2);
        expect(chain.numKeys()).toBe(2);
        expect(chain.numBloomFilterEntries()).toBe(4);
        const filter = chain.getFilter(4, 0.001, 100);
        expect(filter.contains(key1.getPubKey())).toBe(true);
        expect(filter.contains(key1.getPubKeyHash())).toBe(true);
        expect(filter.contains(key2.getPubKey())).toBe(true);
        expect(filter.contains(key2.getPubKeyHash())).toBe(true);
        const key3 = new ECKey();
        expect(filter.contains(key3.getPubKey())).toBe(false);
    });

    test('keysBeforeAndAfter', () => {
        Utils.setMockClock();
        const now = Utils.currentTimeSeconds();
        const key1 = new ECKey();
        Utils.rollMockClock(86400);
        const key2 = new ECKey();
        const keys = [key1, key2];
        expect(chain.importKeys(keys)).toBe(2);

        expect(chain.findOldestKeyAfter(now + 86400 * 2)).toBeNull();
        expect(chain.findOldestKeyAfter(now - 1)).toEqual(key1);
        expect(chain.findOldestKeyAfter(now + 86400 - 1)).toEqual(key2);

        expect(chain.findKeysBefore(now + 86400 * 2).length).toBe(2);
        expect(chain.findKeysBefore(now + 1).length).toBe(1);
        expect(chain.findKeysBefore(now - 1).length).toBe(0);
    });
});
