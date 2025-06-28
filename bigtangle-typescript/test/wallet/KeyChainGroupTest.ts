import { Buffer } from 'buffer';
import { KeyChainGroup } from '../../src/net/bigtangle/wallet/KeyChainGroup';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { DeterministicKey } from '../../src/net/bigtangle/crypto/DeterministicKey';
import { KeyChain } from '../../src/net/bigtangle/wallet/KeyChain';
import { ECKey } from '../../src/net/bigtangle/core/ECKey';
import { Sha256Hash } from '../../src/net/bigtangle/core/Sha256Hash';
import { DeterministicSeed } from '../../src/net/bigtangle/wallet/DeterministicSeed';
import { MnemonicCode } from '../../src/net/bigtangle/crypto/MnemonicCode';
import { MarriedKeyChain } from '../../src/net/bigtangle/wallet/MarriedKeyChain';
import { Address } from '../../src/net/bigtangle/core/Address';
import { RedeemData } from '../../src/net/bigtangle/wallet/RedeemData';
import { KeyCrypterScrypt } from '../../src/net/bigtangle/crypto/KeyCrypterScrypt';
import { BloomFilter } from '../../src/net/bigtangle/core/BloomFilter';
import { Utils } from '../../src/net/bigtangle/utils/Utils';
import { BigInteger } from '../../src/net/bigtangle/core/BigInteger';

describe('KeyChainGroupTest', () => {
    const LOOKAHEAD_SIZE = 5;
    const PARAMS = MainNetParams.get();
    const XPUB =
        'xpub68KFnj3bqUx1s7mHejLDBPywCAKdJEu1b49uniEEn2WSbHmZ7xbLqFTjJbtx1LUcAt1DwhoqWHmo2s5WMJp6wi38CiF2hYD49qVViKVvAoi';
    let group: KeyChainGroup;
    let watchingAccountKey: DeterministicKey;

    beforeEach(() => {
        Utils.setMockClock();
        group = new KeyChainGroup(PARAMS);
        group.setLookaheadSize(LOOKAHEAD_SIZE);
        group.getActiveKeyChain();

        watchingAccountKey = DeterministicKey.deserializeB58(null, XPUB, PARAMS);
    });

    function createMarriedKeyChainGroup(): KeyChainGroup {
        const group = new KeyChainGroup(PARAMS);
        const chain = createMarriedKeyChain();
        group.addAndActivateHDChain(chain);
        group.setLookaheadSize(LOOKAHEAD_SIZE);
        group.getActiveKeyChain();
        return group;
    }

    function createMarriedKeyChain(): MarriedKeyChain {
        const entropy = Sha256Hash.hash(
            Buffer.from("don't use a seed like this in real life"),
        );
        const seed = new DeterministicSeed(
            entropy,
            '',
            MnemonicCode.BIP39_STANDARDISATION_TIME_SECS,
        );
        const chain = MarriedKeyChain.builder()
            .seed(seed)
            .followingKeys(watchingAccountKey)
            .threshold(2)
            .build();
        return chain;
    }

    test('freshCurrentKeys', () => {
        let numKeys =
            (group.getLookaheadSize() + group.getLookaheadThreshold()) * 2 +
            1 +
            group.getActiveKeyChain().getAccountPath().length +
            2;
        expect(group.numKeys()).toBe(numKeys);
        expect(group.getBloomFilterElementCount()).toBe(2 * numKeys);
        const r1 = group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(group.numKeys()).toBe(numKeys);
        expect(group.getBloomFilterElementCount()).toBe(2 * numKeys);

        const i1 = new ECKey();
        group.importKeys(i1);
        numKeys++;
        expect(group.numKeys()).toBe(numKeys);
        expect(group.getBloomFilterElementCount()).toBe(2 * numKeys);

        const r2 = group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(r1).toEqual(r2);
        const c1 = group.currentKey(KeyChain.KeyPurpose.CHANGE);
        expect(r1).not.toEqual(c1);
        const r3 = group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(r1).not.toEqual(r3);
        const c2 = group.freshKey(KeyChain.KeyPurpose.CHANGE);
        expect(r3).not.toEqual(c2);
        const r4 = group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(r2).toEqual(r4);
        const c3 = group.currentKey(KeyChain.KeyPurpose.CHANGE);
        expect(c1).toEqual(c3);
        group.markPubKeyAsUsed(r4.getPubKey());
        const r5 = group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(r4).not.toEqual(r5);
    });

    test('freshCurrentKeysForMarriedKeychain', () => {
        group = createMarriedKeyChainGroup();

        expect(() => {
            group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        }).toThrow();

        expect(() => {
            group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        }).toThrow();
    });

    test('imports', () => {
        const key1 = new ECKey();
        const numKeys = group.numKeys();
        expect(group.removeImportedKey(key1)).toBe(false);
        expect(group.importKeys([key1])).toBe(1);
        expect(group.numKeys()).toBe(numKeys + 1);
        group.removeImportedKey(key1);
        expect(group.numKeys()).toBe(numKeys);
    });

    test('findKey', () => {
        const a = group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        const b = group.freshKey(KeyChain.KeyPurpose.CHANGE);
        const c = new ECKey();
        const d = new ECKey();
        group.importKeys(c);
        expect(group.hasKey(a)).toBe(true);
        expect(group.hasKey(b)).toBe(true);
        expect(group.hasKey(c)).toBe(true);
        expect(group.hasKey(d)).toBe(false);
        let result = group.findKeyFromPubKey(a.getPubKey());
        expect(a).toEqual(result);
        result = group.findKeyFromPubKey(b.getPubKey());
        expect(b).toEqual(result);
        result = group.findKeyFromPubHash(a.getPubKeyHash());
        expect(a).toEqual(result);
        result = group.findKeyFromPubHash(b.getPubKeyHash());
        expect(b).toEqual(result);
        result = group.findKeyFromPubKey(c.getPubKey());
        expect(c).toEqual(result);
        result = group.findKeyFromPubHash(c.getPubKeyHash());
        expect(c).toEqual(result);
        expect(group.findKeyFromPubKey(d.getPubKey())).toBeNull();
        expect(group.findKeyFromPubHash(d.getPubKeyHash())).toBeNull();
    });

    test('currentP2SHAddress', () => {
        group = createMarriedKeyChainGroup();
        const a1 = group.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(a1.isP2SHAddress()).toBe(true);
        const a2 = group.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(a1).toEqual(a2);
        const a3 = group.currentAddress(KeyChain.KeyPurpose.CHANGE);
        expect(a2).not.toEqual(a3);
    });

    test('freshAddress', () => {
        group = createMarriedKeyChainGroup();
        const a1 = group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        const a2 = group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(a1.isP2SHAddress()).toBe(true);
        expect(a1).not.toEqual(a2);
        group.getBloomFilterElementCount();
        expect(group.numKeys()).toBe(
            (group.getLookaheadSize() + group.getLookaheadThreshold()) * 2 +
                (2 - group.getLookaheadThreshold()) +
                group.getActiveKeyChain().getAccountPath().length +
                3,
        );

        const a3 = group.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(a2).toEqual(a3);
    });

    test('findRedeemData', () => {
        group = createMarriedKeyChainGroup();

        expect(group.findRedeemDataFromScriptHash(new ECKey().getPubKey())).toBeNull();

        const address = group.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        const redeemData = group.findRedeemDataFromScriptHash(address.getHash160());
        expect(redeemData).not.toBeNull();
        expect(redeemData.redeemScript).not.toBeNull();
        expect(redeemData.keys.length).toBe(2);
    });

    test('encryptionWithoutImported', () => {
        encryption(false);
    });

    test('encryptionWithImported', () => {
        encryption(true);
    });

    function encryption(withImported: boolean) {
        Utils.rollMockClock(0);
        const now = Utils.currentTimeSeconds();
        const a = group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(group.getEarliestKeyCreationTime()).toBe(now);
        Utils.rollMockClock(-86400);
        const yesterday = Utils.currentTimeSeconds();
        const b = new ECKey();

        expect(group.isEncrypted()).toBe(false);
        expect(() => {
            group.checkPassword('foo');
        }).toThrow();
        if (withImported) {
            expect(group.getEarliestKeyCreationTime()).toBe(now);
            group.importKeys(b);
            expect(group.getEarliestKeyCreationTime()).toBe(yesterday);
        }
        const scrypt = new KeyCrypterScrypt(2);
        const aesKey = scrypt.deriveKey('password');
        group.encrypt(scrypt, aesKey);
        expect(group.isEncrypted()).toBe(true);
        expect(group.checkPassword('password')).toBe(true);
        expect(group.checkPassword('wrong password')).toBe(false);
        const ea = group.findKeyFromPubKey(a.getPubKey());
        expect(ea.isEncrypted()).toBe(true);
        if (withImported) {
            expect(group.findKeyFromPubKey(b.getPubKey()).isEncrypted()).toBe(true);
            expect(group.getEarliestKeyCreationTime()).toBe(yesterday);
        } else {
            expect(group.getEarliestKeyCreationTime()).toBe(now);
        }
        expect(() => {
            ea.sign(Sha256Hash.ZERO_HASH);
        }).toThrow();
        if (withImported) {
            const c = new ECKey();
            expect(() => {
                group.importKeys(c);
            }).toThrow();
            group.importKeysAndEncrypt([c], aesKey);
            const ec = group.findKeyFromPubKey(c.getPubKey());
            expect(() => {
                group.importKeysAndEncrypt([ec], aesKey);
            }).toThrow();
        }

        expect(() => {
            group.decrypt(scrypt.deriveKey('WRONG PASSWORD'));
        }).toThrow();

        group.decrypt(aesKey);
        expect(group.isEncrypted()).toBe(false);
        expect(group.findKeyFromPubKey(a.getPubKey()).isEncrypted()).toBe(false);
        if (withImported) {
            expect(group.findKeyFromPubKey(b.getPubKey()).isEncrypted()).toBe(false);
            expect(group.getEarliestKeyCreationTime()).toBe(yesterday);
        } else {
            expect(group.getEarliestKeyCreationTime()).toBe(now);
        }
    }

    test('encryptionWhilstEmpty', () => {
        group = new KeyChainGroup(PARAMS);
        group.setLookaheadSize(5);
        const scrypt = new KeyCrypterScrypt(2);
        const aesKey = scrypt.deriveKey('password');
        group.encrypt(scrypt, aesKey);
        expect(group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS).isEncrypted()).toBe(
            true,
        );
        const key = group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        group.decrypt(aesKey);
        expect(group.findKeyFromPubKey(key.getPubKey()).isEncrypted()).toBe(false);
    });

    test('bloom', () => {
        const key1 = group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        const key2 = new ECKey();
        let filter = group.getBloomFilter(
            group.getBloomFilterElementCount(),
            0.001,
            Math.floor(Math.random() * Number.MAX_SAFE_INTEGER),
        );
        expect(filter.contains(key1.getPubKeyHash())).toBe(true);
        expect(filter.contains(key1.getPubKey())).toBe(true);
        expect(filter.contains(key2.getPubKey())).toBe(false);
        for (let i = 0; i < LOOKAHEAD_SIZE + group.getLookaheadThreshold(); i++) {
            const k = group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
            expect(filter.contains(k.getPubKeyHash())).toBe(true);
        }
        expect(
            filter.contains(
                group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS).getPubKey(),
            ),
        ).toBe(false);
        group.importKeys(key2);
        filter = group.getBloomFilter(
            group.getBloomFilterElementCount(),
            0.001,
            Math.floor(Math.random() * Number.MAX_SAFE_INTEGER),
        );
        expect(filter.contains(key1.getPubKeyHash())).toBe(true);
        expect(filter.contains(key1.getPubKey())).toBe(true);
        expect(filter.contains(key2.getPubKey())).toBe(true);
    });

    test('findRedeemScriptFromPubHash', () => {
        group = createMarriedKeyChainGroup();
        let address = group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(group.findRedeemDataFromScriptHash(address.getHash160())).not.toBeNull();
        group.getBloomFilterElementCount();
        const group2 = createMarriedKeyChainGroup();
        group2.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        group2.getBloomFilterElementCount();
        for (
            let i = 0;
            i < group.getLookaheadSize() + group.getLookaheadThreshold();
            i++
        ) {
            address = group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
            expect(
                group2.findRedeemDataFromScriptHash(address.getHash160()),
            ).not.toBeNull();
        }
        expect(
            group2.findRedeemDataFromScriptHash(
                group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS).getHash160(),
            ),
        ).toBeNull();
    });

    test('bloomFilterForMarriedChains', () => {
        group = createMarriedKeyChainGroup();
        const bufferSize = group.getLookaheadSize() + group.getLookaheadThreshold();
        const expected = bufferSize * 2 * 2;
        expect(group.getBloomFilterElementCount()).toBe(expected);
        const address1 = group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(group.getBloomFilterElementCount()).toBe(expected);
        const filter = group.getBloomFilter(
            expected + 2,
            0.001,
            Math.floor(Math.random() * Number.MAX_SAFE_INTEGER),
        );
        expect(filter.contains(address1.getHash160())).toBe(true);

        const address2 = group.freshAddress(KeyChain.KeyPurpose.CHANGE);
        expect(filter.contains(address2.getHash160())).toBe(true);

        for (let i = 0; i < bufferSize - 1; i++) {
            const address = group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
            expect(filter.contains(address.getHash160())).toBe(true);
        }
        expect(
            filter.contains(
                group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS).getHash160(),
            ),
        ).toBe(false);
    });

    test('earliestKeyTime', () => {
        const now = Utils.currentTimeSeconds();
        const yesterday = now - 86400;
        expect(group.getEarliestKeyCreationTime()).toBe(now);
        Utils.rollMockClock(10000);
        group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        Utils.rollMockClock(10000);
        group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(group.getEarliestKeyCreationTime()).toBe(now);
        const key = new ECKey();
        key.setCreationTimeSeconds(yesterday);
        group.importKeys(key);
        expect(group.getEarliestKeyCreationTime()).toBe(yesterday);
    });

    test('constructFromSeed', () => {
        const key1 = group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        const seed = group.getActiveKeyChain().getSeed();
        const group2 = new KeyChainGroup(PARAMS, seed);
        group2.setLookaheadSize(5);
        const key2 = group2.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(key1).toEqual(key2);
    });

    test('markAsUsed', () => {
        const addr1 = group.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        const addr2 = group.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(addr1).toEqual(addr2);
        group.markPubKeyHashAsUsed(addr1.getHash160());
        const addr3 = group.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        expect(addr2).not.toEqual(addr3);
    });

    test('isNotWatching', () => {
        group = new KeyChainGroup(PARAMS);
        const key = ECKey.fromPrivate(BigInt(10));
        group.importKeys(key);
        expect(group.isWatching()).toBe(false);
    });

    test('isWatching', () => {
        group = new KeyChainGroup(
            PARAMS,
            DeterministicKey.deserializeB58(
                'xpub69bjfJ91ikC5ghsqsVDHNq2dRGaV2HHVx7Y9LXi27LN9BWWAXPTQr4u8U3wAtap8bLdHdkqPpAcZmhMS5SnrMQC4ccaoBccFhh315P4UYzo',
                PARAMS,
            ),
        );
        const watchingKey = ECKey.fromPublicOnly(new ECKey().getPubKeyPoint());
        group.importKeys(watchingKey);
        expect(group.isWatching()).toBe(true);
    });

    test('isWatchingNoKeys', () => {
        expect(() => {
            group = new KeyChainGroup(PARAMS);
            group.isWatching();
        }).toThrow();
    });

    test('isWatchingMixedKeys', () => {
        expect(() => {
            group = new KeyChainGroup(
                PARAMS,
                DeterministicKey.deserializeB58(
                    'xpub69bjfJ91ikC5ghsqsVDHNq2dRGaV2HHVx7Y9LXi27LN9BWWAXPTQr4u8U3wAtap8bLdHdkqPpAcZmhMS5SnrMQC4ccaoBccFhh315P4UYzo',
                    PARAMS,
                ),
            );
            const key = ECKey.fromPrivate(BigInt(10));
            group.importKeys(key);
            group.isWatching();
        }).toThrow();
    });
});
