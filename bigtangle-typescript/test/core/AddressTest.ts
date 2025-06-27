
import { Buffer } from 'buffer';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { Address } from '../../src/net/bigtangle/core/Address';
import { Utils } from '../../src/net/bigtangle/utils/Utils';
import {
    AddressFormatException,
    WrongNetworkException,
} from '../../src/net/bigtangle/exception';
import { ScriptBuilder } from '../../src/net/bigtangle/script/ScriptBuilder';
import { ECKey } from '../../src/net/bigtangle/core/ECKey';
import { DumpedPrivateKey } from '../../src/net/bigtangle/utils/DumpedPrivateKey';
import { Script } from '../../src/net/bigtangle/script/Script';

describe('AddressTest', () => {
    const testParams = MainNetParams.get();
    const mainParams = MainNetParams.get();

    test('stringification', () => {
        // Test a testnet address.
        // const a = new Address(testParams, Buffer.from('fda79a24e50ff70ff42f7d89585da5bd19d9e5cc', 'hex'));
        // expect(a.toString()).toBe('n4eA2nbYqErp7H6jebchxAN59DmNpksexv');
        // expect(a.isP2SHAddress()).toBe(false);

        const b = new Address(
            mainParams,
            Buffer.from('4a22c3c4cbb31e4d03b15550636762bda0baf85a', 'hex'),
        );
        expect(b.toString()).toBe('17kzeh4N8g49GFvdDzSf8PjaPfyoD1MndL');
        expect(b.isP2SHAddress()).toBe(false);
    });

    test('decoding', () => {
        const a = Address.fromBase58(
            testParams,
            'n4eA2nbYqErp7H6jebchxAN59DmNpksexv',
        );
        expect(Utils.HEX.encode(a.getHash160())).toBe(
            'fda79a24e50ff70ff42f7d89585da5bd19d9e5cc',
        );

        const b = Address.fromBase58(
            mainParams,
            '17kzeh4N8g49GFvdDzSf8PjaPfyoD1MndL',
        );
        expect(Utils.HEX.encode(b.getHash160())).toBe(
            '4a22c3c4cbb31e4d03b15550636762bda0baf85a',
        );
    });

    test('errorPaths', () => {
        // Check what happens if we try and decode garbage.
        try {
            Address.fromBase58(mainParams, 'this is not a valid address!');
            fail();
        } catch (e) {
            expect(e).toBeInstanceOf(AddressFormatException);
        }

        // Check the empty case.
        try {
            Address.fromBase58(mainParams, '');
            fail();
        } catch (e) {
            expect(e).toBeInstanceOf(AddressFormatException);
        }

        // Check the case of a mismatched network.
        // try {
        //     Address.fromBase58(mainParams, "n4eA2nbYqErp7H6jebchxAN59DmNpksexv");
        //     fail();
        // } catch (e) {
        //     expect(e).toBeInstanceOf(WrongNetworkException);
        //     expect((e as WrongNetworkException).verCode).toBe(MainNetParams.get().getAddressHeader());
        //     expect(Buffer.compare(Buffer.from((e as WrongNetworkException).acceptableVersions), Buffer.from(MainNetParams.get().getAcceptableAddressCodes()))).toBe(0);
        // }
    });

    test('p2shAddress', () => {
        // Test that we can construct P2SH addresses
        const mainNetP2SHAddress = Address.fromBase58(
            MainNetParams.get(),
            '35b9vsyH1KoFT5a5KtrKusaCcPLkiSo1tU',
        );
        expect(mainNetP2SHAddress.version).toBe(MainNetParams.get().p2shHeader);
        expect(mainNetP2SHAddress.isP2SHAddress()).toBe(true);
        // const testNetP2SHAddress = Address.fromBase58(MainNetParams.get(), "2MuVSxtfivPKJe93EC1Tb9UhJtGhsoWEHCe");
        // expect(testNetP2SHAddress.version).toBe(MainNetParams.get().p2shHeader);
        // expect(testNetP2SHAddress.isP2SHAddress()).toBe(true);

        // Test that we can determine what network a P2SH address belongs to
        const mainNetParams = Address.getParametersFromAddress(
            '35b9vsyH1KoFT5a5KtrKusaCcPLkiSo1tU',
        );
        expect(mainNetParams.getId()).toBe(MainNetParams.get().getId());
        // const testNetParams = Address.getParametersFromAddress("2MuVSxtfivPKJe93EC1Tb9UhJtGhsoWEHCe");
        // expect(testNetParams.getId()).toBe(MainNetParams.get().getId());

        // Test that we can convert them from hashes
        const hex = Buffer.from('2ac4b0b501117cc8119c5797b519538d4942e90e', 'hex');
        const a = Address.fromP2SHHash(mainParams, hex);
        expect(a.toString()).toBe('35b9vsyH1KoFT5a5KtrKusaCcPLkiSo1tU');
    });

    test('p2shAddressCreationFromKeys', () => {
        // import some keys from this example: https://gist.github.com/gavinandresen/3966071
        let key1 = DumpedPrivateKey.fromBase58(
            mainParams,
            '5JaTXbAUmfPYZFRwrYaALK48fN6sFJp4rHqq2QSXs8ucfpE4yQU',
        ).getKey();
        key1 = ECKey.fromPrivate(key1.getPrivKeyBytes());
        let key2 = DumpedPrivateKey.fromBase58(
            mainParams,
            '5Jb7fCeh1Wtm4yBBg3q3XbT6B525i17kVhy3vMC9AqfR6FH2qGk',
        ).getKey();
        key2 = ECKey.fromPrivate(key2.getPrivKeyBytes());
        let key3 = DumpedPrivateKey.fromBase58(
            mainParams,
            '5JFjmGo5Fww9p8gvx48qBYDJNAzR9pmH5S389axMtDyPT8ddqmw',
        ).getKey();
        key3 = ECKey.fromPrivate(key3.getPrivKeyBytes());

        const keys = [key1, key2, key3];
        const p2shScript: Script = ScriptBuilder.createP2SHOutputScript(2, keys);
        const address = Address.fromP2SHScript(mainParams, p2shScript);
        expect(address.toString()).toBe('3N25saC4dT24RphDAwLtD8LUN4E2gZPJke');
    });

    test('roundtripBase58', () => {
        const base58 = '17kzeh4N8g49GFvdDzSf8PjaPfyoD1MndL';
        expect(Address.fromBase58(null, base58).toBase58()).toBe(base58);
    });

    test('comparisonLessThan', () => {
        const a = Address.fromBase58(
            mainParams,
            '1Dorian4RoXcnBv9hnQ4Y2C1an6NJ4UrjX',
        );
        const b = Address.fromBase58(
            mainParams,
            '1EXoDusjGwvnjZUyKkxZ4UHEf77z6A5S4P',
        );

        const result = a.compareTo(b);
        expect(result).toBeLessThan(0);
    });

    test('comparisonGreaterThan', () => {
        const a = Address.fromBase58(
            mainParams,
            '1EXoDusjGwvnjZUyKkxZ4UHEf77z6A5S4P',
        );
        const b = Address.fromBase58(
            mainParams,
            '1Dorian4RoXcnBv9hnQ4Y2C1an6NJ4UrjX',
        );

        const result = a.compareTo(b);
        expect(result).toBeGreaterThan(0);
    });

    test('comparisonBytesVsString', () => {
        // TODO: To properly test this we need a much larger data set
        const a = Address.fromBase58(
            mainParams,
            '1Dorian4RoXcnBv9hnQ4Y2C1an6NJ4UrjX',
        );
        const b = Address.fromBase58(
            mainParams,
            '1EXoDusjGwvnjZUyKkxZ4UHEf77z6A5S4P',
        );

        const resultBytes = a.compareTo(b);
        const resultsString = a.toString().localeCompare(b.toString());
        expect(resultBytes).toBeLessThan(0);
        expect(resultsString).toBeLessThan(0);
    });
});
