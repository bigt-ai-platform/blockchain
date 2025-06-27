import { Buffer } from 'buffer';
import { HDKeyDerivation } from '../../src/net/bigtangle/crypto/HDKeyDerivation';
import { DeterministicKey } from '../../src/net/bigtangle/crypto/DeterministicKey';
import { ChildNumber } from '../../src/net/bigtangle/crypto/ChildNumber';
import { HDUtils } from '../../src/net/bigtangle/crypto/HDUtils';
import { KeyCrypterScrypt } from '../../src/net/bigtangle/crypto/KeyCrypterScrypt';
import { Sha256Hash } from '../../src/net/bigtangle/core/Sha256Hash';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { Utils } from '../../src/net/bigtangle/utils/Utils';

describe('ChildKeyDerivationTest', () => {
    const HDW_CHAIN_EXTERNAL = 0;
    const HDW_CHAIN_INTERNAL = 1;

    test('testChildKeyDerivation', () => {
        const ckdTestVectors = [
            'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
            '04' +
                '6a04ab98d9e4774ad806e302dddeb63b' +
                'ea16b5cb5f223ee77478e861bb583eb3' +
                '36b6fbcb60b5b3d4f1551ac45e5ffc49' +
                '36466e7d98f6c7c0ec736539f74691a6',
            'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',

            'be05d9ded0a73f81b814c93792f753b35c575fe446760005d44e0be13ba8935a',
            '02' +
                'b530da16bbff1428c33020e87fc9e699' +
                'cc9c753a63b8678ce647b7457397acef',
            '7012bc411228495f25d666d55fdce3f10a93908b5f9b9b7baa6e7573603a7bda',
        ];

        for (let i = 0; i < 1; i++) {
            const priv = Utils.HEX.decode(ckdTestVectors[3 * i]);
            const pub = Utils.HEX.decode(ckdTestVectors[3 * i + 1]);
            const chain = Utils.HEX.decode(ckdTestVectors[3 * i + 2]); // chain code

            const ekprv = HDKeyDerivation.createMasterPrivKeyFromBytes(priv, chain);

            const ekprv_0 = HDKeyDerivation.deriveChildKey(ekprv, 0);
            const ekprv_1 = HDKeyDerivation.deriveChildKey(ekprv, 1);

            const ekprv_0_EX = HDKeyDerivation.deriveChildKey(
                ekprv_0,
                HDW_CHAIN_EXTERNAL,
            );
            const ekprv_0_IN = HDKeyDerivation.deriveChildKey(
                ekprv_0,
                HDW_CHAIN_INTERNAL,
            );

            const ekprv_0_EX_0 = HDKeyDerivation.deriveChildKey(ekprv_0_EX, 0);
            const ekprv_0_EX_1 = HDKeyDerivation.deriveChildKey(ekprv_0_EX, 1);
            const ekprv_0_EX_2 = HDKeyDerivation.deriveChildKey(ekprv_0_EX, 2);

            const ekprv_0_IN_0 = HDKeyDerivation.deriveChildKey(ekprv_0_IN, 0);
            const ekprv_0_IN_1 = HDKeyDerivation.deriveChildKey(ekprv_0_IN, 1);
            const ekprv_0_IN_2 = HDKeyDerivation.deriveChildKey(ekprv_0_IN, 2);

            const ekprv_1_IN = HDKeyDerivation.deriveChildKey(
                ekprv_1,
                HDW_CHAIN_INTERNAL,
            );
            const ekprv_1_IN_4095 = HDKeyDerivation.deriveChildKey(ekprv_1_IN, 4095);

            const ekpub = HDKeyDerivation.createMasterPubKeyFromBytes(
                HDUtils.toCompressed(pub),
                chain,
            );

            const ekpub_0 = HDKeyDerivation.deriveChildKey(ekpub, 0);
            const ekpub_1 = HDKeyDerivation.deriveChildKey(ekpub, 1);

            const ekpub_0_EX = HDKeyDerivation.deriveChildKey(
                ekpub_0,
                HDW_CHAIN_EXTERNAL,
            );
            const ekpub_0_IN = HDKeyDerivation.deriveChildKey(
                ekpub_0,
                HDW_CHAIN_INTERNAL,
            );

            const ekpub_0_EX_0 = HDKeyDerivation.deriveChildKey(ekpub_0_EX, 0);
            const ekpub_0_EX_1 = HDKeyDerivation.deriveChildKey(ekpub_0_EX, 1);
            const ekpub_0_EX_2 = HDKeyDerivation.deriveChildKey(ekpub_0_EX, 2);

            const ekpub_0_IN_0 = HDKeyDerivation.deriveChildKey(ekpub_0_IN, 0);
            const ekpub_0_IN_1 = HDKeyDerivation.deriveChildKey(ekpub_0_IN, 1);
            const ekpub_0_IN_2 = HDKeyDerivation.deriveChildKey(ekpub_0_IN, 2);

            const ekpub_1_IN = HDKeyDerivation.deriveChildKey(
                ekpub_1,
                HDW_CHAIN_INTERNAL,
            );
            const ekpub_1_IN_4095 = HDKeyDerivation.deriveChildKey(ekpub_1_IN, 4095);

            expect(hexEncodePub(ekprv.dropPrivateBytes().dropParent())).toBe(
                hexEncodePub(ekpub),
            );
            expect(hexEncodePub(ekprv_0.dropPrivateBytes().dropParent())).toBe(
                hexEncodePub(ekpub_0),
            );
            expect(hexEncodePub(ekprv_1.dropPrivateBytes().dropParent())).toBe(
                hexEncodePub(ekpub_1),
            );
            expect(hexEncodePub(ekprv_0_IN.dropPrivateBytes().dropParent())).toBe(
                hexEncodePub(ekpub_0_IN),
            );
            expect(hexEncodePub(ekprv_0_IN_0.dropPrivateBytes().dropParent())).toBe(
                hexEncodePub(ekpub_0_IN_0),
            );
            expect(hexEncodePub(ekprv_0_IN_1.dropPrivateBytes().dropParent())).toBe(
                hexEncodePub(ekpub_0_IN_1),
            );
            expect(hexEncodePub(ekprv_0_IN_2.dropPrivateBytes().dropParent())).toBe(
                hexEncodePub(ekpub_0_IN_2),
            );
            expect(hexEncodePub(ekprv_0_EX_0.dropPrivateBytes().dropParent())).toBe(
                hexEncodePub(ekpub_0_EX_0),
            );
            expect(hexEncodePub(ekprv_0_EX_1.dropPrivateBytes().dropParent())).toBe(
                hexEncodePub(ekpub_0_EX_1),
            );
            expect(hexEncodePub(ekprv_0_EX_2.dropPrivateBytes().dropParent())).toBe(
                hexEncodePub(ekpub_0_EX_2),
            );
            expect(hexEncodePub(ekprv_1_IN.dropPrivateBytes().dropParent())).toBe(
                hexEncodePub(ekpub_1_IN),
            );
            expect(
                hexEncodePub(ekprv_1_IN_4095.dropPrivateBytes().dropParent()),
            ).toBe(hexEncodePub(ekpub_1_IN_4095));
        }
    });

    test('inverseEqualsNormal', () => {
        const key1 = HDKeyDerivation.createMasterPrivateKey(
            Buffer.from(
                'Wired / Aug 13th 2014 / Snowden: I Left the NSA Clues, But They Couldn\'t Find Them',
            ),
        );
        const key2 = HDKeyDerivation.deriveChildKeyBytesFromPublic(
            key1.dropPrivateBytes().dropParent(),
            ChildNumber.ZERO,
            HDKeyDerivation.PublicDeriveMode.NORMAL,
        );
        const key3 = HDKeyDerivation.deriveChildKeyBytesFromPublic(
            key1.dropPrivateBytes().dropParent(),
            ChildNumber.ZERO,
            HDKeyDerivation.PublicDeriveMode.WITH_INVERSION,
        );
        expect(Buffer.compare(key2.keyBytes, key3.keyBytes)).toBe(0);
        expect(Buffer.compare(key2.chainCode, key3.chainCode)).toBe(0);
    });

    test('encryptedDerivation', () => {
        const scrypter = new KeyCrypterScrypt();
        const aesKey = scrypter.deriveKey('we never went to the moon');

        const key1 = HDKeyDerivation.createMasterPrivateKey(
            Buffer.from('it was all a hoax'),
        );
        const encryptedKey1 = key1.encrypt(scrypter, aesKey, null);
        const decryptedKey1 = encryptedKey1.decrypt(aesKey);
        expect(key1).toEqual(decryptedKey1);

        const key2 = HDKeyDerivation.deriveChildKey(key1, ChildNumber.ZERO);
        const derivedKey2 = HDKeyDerivation.deriveChildKey(
            encryptedKey1,
            ChildNumber.ZERO,
        );
        expect(derivedKey2.isEncrypted()).toBe(true);
        const decryptedKey2 = derivedKey2.decrypt(aesKey);
        expect(decryptedKey2.isEncrypted()).toBe(false);
        expect(key2).toEqual(decryptedKey2);

        const hash = Sha256Hash.of(
            Buffer.from('the mainstream media won\'t cover it. why is that?'),
        );
        try {
            derivedKey2.sign(hash);
            fail();
        } catch (e) {
            // Ignored.
        }
        const signature = derivedKey2.sign(hash, aesKey);
        expect(derivedKey2.verify(hash, signature)).toBe(true);
    });

    test('pubOnlyDerivation', () => {
        let key1 = HDKeyDerivation.createMasterPrivateKey(
            Buffer.from('satoshi lives!'),
        );
        expect(key1.isPubKeyOnly()).toBe(false);
        let key2 = HDKeyDerivation.deriveChildKey(key1, ChildNumber.ZERO_HARDENED);
        expect(key2.isPubKeyOnly()).toBe(false);
        const key3 = HDKeyDerivation.deriveChildKey(key2, ChildNumber.ZERO);
        expect(key3.isPubKeyOnly()).toBe(false);

        key2 = key2.dropPrivateBytes();
        expect(key2.isPubKeyOnly()).toBe(false);

        const pubkey2 = key2.dropParent();

        const pubkey3 = HDKeyDerivation.deriveChildKey(pubkey2, ChildNumber.ZERO);
        expect(pubkey3.isPubKeyOnly()).toBe(true);
        expect(key3.getPubKeyPoint()).toEqual(pubkey3.getPubKeyPoint());
    });

    test('testSerializationMainAndTestNetworks', () => {
        const key1 = HDKeyDerivation.createMasterPrivateKey(
            Buffer.from('satoshi lives!'),
        );
        const params = MainNetParams.get();
        const pub58 = key1.serializePubB58(params);
        const priv58 = key1.serializePrivB58(params);
        expect(pub58).toBe(
            'xpub661MyMwAqRbcF7mq7Aejj5xZNzFfgi3ABamE9FedDHVmViSzSxYTgAQGcATDo2J821q7Y9EAagjg5EP3L7uBZk11PxZU3hikL59dexfLkz3',
        );
        expect(priv58).toBe(
            'xprv9s21ZrQH143K2dhN197jMx1ppxRBHFKJpMqdLsF1ewxncv7quRED8N5nksxphju3W7naj1arF56L5PUEWfuSk8h73Sb2uh7bSwyXNrjzhAZ',
        );
    });

    test('serializeToTextAndBytes', () => {
        const key1 = HDKeyDerivation.createMasterPrivateKey(
            Buffer.from('satoshi lives!'),
        );
        const key2 = HDKeyDerivation.deriveChildKey(key1, ChildNumber.ZERO_HARDENED);

        key1.setCreationTimeSeconds(0);
        const params = MainNetParams.get();

        const pub58 = key1.serializePubB58(params);
        const priv58 = key1.serializePrivB58(params);
        const pub = key1.serializePublic(params);
        const priv = key1.serializePrivate(params);
        expect(pub58).toBe(
            'xpub661MyMwAqRbcF7mq7Aejj5xZNzFfgi3ABamE9FedDHVmViSzSxYTgAQGcATDo2J821q7Y9EAagjg5EP3L7uBZk11PxZU3hikL59dexfLkz3',
        );
        expect(priv58).toBe(
            'xprv9s21ZrQH143K2dhN197jMx1ppxRBHFKJpMqdLsF1ewxncv7quRED8N5nksxphju3W7naj1arF56L5PUEWfuSk8h73Sb2uh7bSwyXNrjzhAZ',
        );
        expect(
            DeterministicKey.deserializeB58(null, priv58, params),
        ).toEqual(key1);
        expect(DeterministicKey.deserializeB58(priv58, params)).toEqual(key1);
        expect(
            DeterministicKey.deserializeB58(null, pub58, params).getPubKeyPoint(),
        ).toEqual(key1.getPubKeyPoint());
        expect(
            DeterministicKey.deserializeB58(pub58, params).getPubKeyPoint(),
        ).toEqual(key1.getPubKeyPoint());
        expect(DeterministicKey.deserialize(params, priv, null)).toEqual(key1);
        expect(DeterministicKey.deserialize(params, priv)).toEqual(key1);
        expect(
            DeterministicKey.deserialize(params, pub, null).getPubKeyPoint(),
        ).toEqual(key1.getPubKeyPoint());
        expect(
            DeterministicKey.deserialize(params, pub).getPubKeyPoint(),
        ).toEqual(key1.getPubKeyPoint());

        const pub58_2 = key2.serializePubB58(params);
        const priv58_2 = key2.serializePrivB58(params);
        const pub_2 = key2.serializePublic(params);
        const priv_2 = key2.serializePrivate(params);
        expect(DeterministicKey.deserializeB58(key1, priv58_2, params)).toEqual(
            key2,
        );
        expect(
            DeterministicKey.deserializeB58(key1, pub58_2, params).getPubKeyPoint(),
        ).toEqual(key2.getPubKeyPoint());
        expect(DeterministicKey.deserialize(params, priv_2, key1)).toEqual(key2);
        expect(
            DeterministicKey.deserialize(params, pub_2, key1).getPubKeyPoint(),
        ).toEqual(key2.getPubKeyPoint());
    });

    test('parentlessDeserialization', () => {
        const params = MainNetParams.get();
        const key1 = HDKeyDerivation.createMasterPrivateKey(
            Buffer.from('satoshi lives!'),
        );
        const key2 = HDKeyDerivation.deriveChildKey(key1, ChildNumber.ZERO_HARDENED);
        const key3 = HDKeyDerivation.deriveChildKey(key2, ChildNumber.ZERO_HARDENED);
        const key4 = HDKeyDerivation.deriveChildKey(key3, ChildNumber.ZERO_HARDENED);
        expect(key4.getPath().length).toBe(3);
        expect(
            DeterministicKey.deserialize(
                params,
                key4.serializePrivate(params),
                key3,
            ).getPath().length,
        ).toBe(3);
        expect(
            DeterministicKey.deserialize(
                params,
                key4.serializePrivate(params),
                null,
            ).getPath().length,
        ).toBe(1);
        expect(
            DeterministicKey.deserialize(
                params,
                key4.serializePrivate(params),
            ).getPath().length,
        ).toBe(1);
    });

    test('reserialization', () => {
        let encoded =
            'xpub6D4BDPcP2GT577Vvch3R8wDkScZWzQzMMUm3PWbmWvVJrZwQY4VUNgqFJPMM3No2dFDFGTsxxpG5uJh7n7epu4trkrX7x7DogT5Uv6fcLW5';
        let key = DeterministicKey.deserializeB58(encoded, MainNetParams.get());
        expect(key.serializePubB58(MainNetParams.get())).toBe(encoded);
        expect(key.getDepth()).toBe(3);
        expect(key.getPath().length).toBe(1);
        expect(key.getParentFingerprint()).toBe(0xbef5a2f9);

        encoded =
            'xprv9z4pot5VBttmtdRTWfWQmoH1taj2axGVzFqSb8C9xaxKymcFzXBDptWmT7FwuEzG3ryjH4ktypQSAewRiNMjANTtpgP4mLTj34bhnZX7UiM';
        key = DeterministicKey.deserializeB58(encoded, MainNetParams.get());
        expect(key.serializePrivB58(MainNetParams.get())).toBe(encoded);
        expect(key.getDepth()).toBe(3);
        expect(key.getPath().length).toBe(1);
        expect(key.getParentFingerprint()).toBe(0xbef5a2f9);

        expect(
            DeterministicKey.deserializeB58(
                'xpub661MyMwAqRbcFW31YEwpkMuc5THy2PSt5bDMsktWQcFF8syAmRUapSCGu8ED9W6oDMSgv6Zz8idoc4a6mr8BDzTJY47LJhkJ8UB7WEGuduB',
                MainNetParams.get(),
            ).getParentFingerprint(),
        ).toBe(0);
        expect(
            DeterministicKey.deserializeB58(
                'xprv9s21ZrQH143K31xYSDQpPDxsXRTUcvj2iNHm5NUtrGiGG5e2DtALGdso3pGz6ssrdK4PFmM8NSpSBHNqPqm55Qn3LqFtT2emdEXVYsCzC2U',
                MainNetParams.get(),
            ).getParentFingerprint(),
        ).toBe(0);
    });

    function hexEncodePub(pubKey: DeterministicKey): string {
        return Utils.HEX.encode(pubKey.getPubKey());
    }
});
