
import { Buffer } from 'buffer';
import { DeterministicKeyChain } from '../../src/net/bigtangle/wallet/DeterministicKeyChain';
import { Sha256Hash } from '../../src/net/bigtangle/core/Sha256Hash';
import { KeyChain } from '../../src/net/bigtangle/wallet/KeyChain';
import { Address } from '../../src/net/bigtangle/core/Address';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { ECKey } from '../../src/net/bigtangle/core/ECKey';
import { ChildNumber } from '../../src/net/bigtangle/crypto/ChildNumber';
import { DeterministicKey } from '../../src/net/bigtangle/crypto/DeterministicKey';
import { BloomFilter } from '../../src/net/bigtangle/core/BloomFilter';
import { Utils } from '../../src/net/bigtangle/utils/Utils';

class AccountOneChain extends DeterministicKeyChain {
    public constructor(entropy: Buffer, s: string, secs: number) {
        super(entropy, s, secs);
    }

    protected getAccountPath(): ChildNumber[] {
        return [ChildNumber.ONE];
    }
}

describe('DeterministicKeyChainTest', () => {
    let chain: DeterministicKeyChain;
    const ENTROPY = Sha256Hash.hash(
        Buffer.from("don't use a string seed like this in real life"),
    );

    beforeEach(() => {
        const secs = 1389353062;
        chain = new DeterministicKeyChain(ENTROPY, '', secs);
        chain.setLookaheadSize(10);
        expect(chain.getSeed().getCreationTimeSeconds()).toBe(secs);
    });

    test('derive', () => {
        const key1 = chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(key1.isPubKeyOnly()).toBe(false);
        const key2 = chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(key2.isPubKeyOnly()).toBe(false);

        const address = Address.fromBase58(
            MainNetParams.get(),
            'n1bQNoEx8uhmCzzA5JPG6sFdtsUQhwiQJV',
        );
        expect(key1.toAddress(MainNetParams.get())).toEqual(address);
        expect(key2.toAddress(MainNetParams.get()).toString()).toBe(
            'mnHUcqUVvrfi5kAaXJDQzBb9HsWs78b42R',
        );
        expect(chain.findKeyFromPubHash(address.getHash160())).toEqual(key1);
        expect(chain.findKeyFromPubKey(key2.getPubKey())).toEqual(key2);

        key1.sign(Sha256Hash.ZERO_HASH);
        expect(key1.isPubKeyOnly()).toBe(false);

        const key3 = chain.getKey(KeyChain.KeyPurpose.CHANGE);
        expect(key3.isPubKeyOnly()).toBe(false);
        expect(key3.toAddress(MainNetParams.get()).toString()).toBe(
            'mqumHgVDqNzuXNrszBmi7A2UpmwaPMx4HQ',
        );
        key3.sign(Sha256Hash.ZERO_HASH);
        expect(key3.isPubKeyOnly()).toBe(false);
    });

    test('getKeys', () => {
        chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        chain.getKey(KeyChain.KeyPurpose.CHANGE);
        chain.maybeLookAhead();
        expect(chain.getKeys(false).length).toBe(2);
    });

    test('deriveAccountOne', () => {
        const secs = 1389353062;
        const chain1 = new AccountOneChain(ENTROPY, '', secs);
        const key1 = chain1.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        const key2 = chain1.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);

        const address = Address.fromBase58(
            MainNetParams.get(),
            'n2nHHRHs7TiZScTuVhZUkzZfTfVgGYwy6X',
        );
        expect(key1.toAddress(MainNetParams.get())).toEqual(address);
        expect(key2.toAddress(MainNetParams.get()).toString()).toBe(
            'mnp2j9za5zMuz44vNxrJCXXhZsCdh89QXn',
        );
        expect(chain1.findKeyFromPubHash(address.getHash160())).toEqual(key1);
        expect(chain1.findKeyFromPubKey(key2.getPubKey())).toEqual(key2);

        key1.sign(Sha256Hash.ZERO_HASH);

        const key3 = chain1.getKey(KeyChain.KeyPurpose.CHANGE);
        expect(key3.toAddress(MainNetParams.get()).toString()).toBe(
            'mpjRhk13rvV7vmnszcUQVYVQzy4HLTPTQU',
        );
        key3.sign(Sha256Hash.ZERO_HASH);
    });

    test('signMessage', () => {
        const key = chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        key.verifyMessage('test', key.signMessage('test'));
    });

    test('random', () => {
        chain = new DeterministicKeyChain(new Utils.SecureRandom(), 384);
        chain.setLookaheadSize(10);
        chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS).sign(Sha256Hash.ZERO_HASH);
        chain.getKey(KeyChain.KeyPurpose.CHANGE).sign(Sha256Hash.ZERO_HASH);
    });

    test('notEncrypted', () => {
        expect(() => {
            chain.toDecrypted('fail');
        }).toThrow();
    });

    test('encryptTwice', () => {
        expect(() => {
            chain = chain.toEncrypted('once');
            chain = chain.toEncrypted('twice');
        }).toThrow();
    });

    test('encryption', () => {
        const key1 = chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        let encChain = chain.toEncrypted('open secret');
        const encKey1 = encChain.findKeyFromPubKey(key1.getPubKey());
        checkEncryptedKeyChain(encChain, key1);

        const decChain = encChain.toDecrypted('open secret');
        const decKey1 = decChain.findKeyFromPubHash(encKey1.getPubKeyHash());
        expect(decKey1.getPubKeyPoint()).toEqual(encKey1.getPubKeyPoint());
        expect(decKey1.isEncrypted()).toBe(false);
        expect(encKey1.getParent()).not.toEqual(decKey1.getParent());
        decChain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS).sign(Sha256Hash.ZERO_HASH);
        decChain.getKey(KeyChain.KeyPurpose.CHANGE).sign(Sha256Hash.ZERO_HASH);
    });

    function checkEncryptedKeyChain(
        encChain: DeterministicKeyChain,
        key1: DeterministicKey,
    ) {
        const encKey1 = encChain.findKeyFromPubKey(key1.getPubKey());
        const encKey2 = encChain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(key1.isEncrypted()).toBe(false);
        expect(encKey1.isEncrypted()).toBe(true);
        expect(encKey1.getPubKeyPoint()).toEqual(key1.getPubKeyPoint());
        const aesKey = encChain.getKeyCrypter().deriveKey('open secret');
        encKey1.sign(Sha256Hash.ZERO_HASH, aesKey);
        encKey2.sign(Sha256Hash.ZERO_HASH, aesKey);
        expect(encChain.checkAESKey(aesKey)).toBe(true);
        expect(encChain.checkPassword('access denied')).toBe(false);
        expect(encChain.checkPassword('open secret')).toBe(true);
    }

    test('watchingChain', () => {
        Utils.setMockClock();
        const key1 = chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        const key2 = chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        const key3 = chain.getKey(KeyChain.KeyPurpose.CHANGE);
        const key4 = chain.getKey(KeyChain.KeyPurpose.CHANGE);

        const params = MainNetParams.get();
        let watchingKey = chain.getWatchingKey();
        const pub58 = watchingKey.serializePubB58(params);
        expect(pub58).toBe(
            'xpub69KR9epSNBM59KLuasxMU5CyKytMJjBP5HEZ5p8YoGUCpM6cM9hqxB9DDPCpUUtqmw5duTckvPfwpoWGQUFPmRLpxs5jYiTf2u6xRMcdhDf',
        );
        watchingKey = DeterministicKey.deserializeB58(null, pub58, params);
        watchingKey.setCreationTimeSeconds(100000);
        chain = DeterministicKeyChain.watch(watchingKey);
        expect(chain.getEarliestKeyCreationTime()).toBe(100000);
        chain.setLookaheadSize(10);
        chain.maybeLookAhead();

        expect(
            chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS).getPubKeyPoint(),
        ).toEqual(key1.getPubKeyPoint());
        expect(
            chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS).getPubKeyPoint(),
        ).toEqual(key2.getPubKeyPoint());
        const key = chain.getKey(KeyChain.KeyPurpose.CHANGE);
        expect(key.getPubKeyPoint()).toEqual(key3.getPubKeyPoint());
        expect(() => {
            key.sign(Sha256Hash.ZERO_HASH);
        }).toThrow();
    });

    test('watchingCannotEncrypt', () => {
        expect(() => {
            const accountKey = chain.getKeyByPath(
                DeterministicKeyChain.ACCOUNT_ZERO_PATH,
            );
            chain = DeterministicKeyChain.watch(
                accountKey.dropPrivateBytes().dropParent(),
            );
            chain = chain.toEncrypted("this doesn't make any sense");
        }).toThrow();
    });

    test('bloom1', () => {
        const key2 = chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        const key1 = chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);

        const numEntries =
            ((chain.getLookaheadSize() + chain.getLookaheadThreshold()) * 2 +
                chain.numLeafKeysIssued() +
                4) *
            2;
        expect(chain.numBloomFilterEntries()).toBe(numEntries);
        const filter = chain.getFilter(numEntries, 0.001, 1);
        expect(filter.contains(key1.getPubKey())).toBe(true);
        expect(filter.contains(key1.getPubKeyHash())).toBe(true);
        expect(filter.contains(key2.getPubKey())).toBe(true);
        expect(filter.contains(key2.getPubKeyHash())).toBe(true);
    });

    test('bloom2', () => {
        const keys: DeterministicKey[] = [];
        for (let i = 0; i < 100; i++) {
            keys.push(chain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS));
        }
        chain = DeterministicKeyChain.watch(
            chain.getWatchingKey().dropPrivateBytes().dropParent(),
        );
        const e = chain.numBloomFilterEntries();
        const filter = chain.getFilter(e, 0.001, 1);
        for (const key of keys) {
            expect(filter.contains(key.getPubKeyHash())).toBe(true);
        }
    });
});
