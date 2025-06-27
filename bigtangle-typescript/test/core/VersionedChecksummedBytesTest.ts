
import { Buffer } from 'buffer';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { VersionedChecksummedBytes } from '../../src/net/bigtangle/core/VersionedChecksummedBytes';
import { Utils } from '../../src/net/bigtangle/utils/Utils';

describe('VersionedChecksummedBytesTest', () => {
    const testParams = MainNetParams.get();
    const mainParams = MainNetParams.get();

    test('stringification', () => {
        // Test a testnet address.
        const a = new VersionedChecksummedBytes(
            testParams.getAddressHeader(),
            Utils.HEX.decode('fda79a24e50ff70ff42f7d89585da5bd19d9e5cc'),
        );
        expect(a.toString()).toBe('n4eA2nbYqErp7H6jebchxAN59DmNpksexv');

        const b = new VersionedChecksummedBytes(
            mainParams.getAddressHeader(),
            Utils.HEX.decode('4a22c3c4cbb31e4d03b15550636762bda0baf85a'),
        );
        expect(b.toString()).toBe('17kzeh4N8g49GFvdDzSf8PjaPfyoD1MndL');
    });

    test('cloning', () => {
        const a = new VersionedChecksummedBytes(
            testParams.getAddressHeader(),
            Utils.HEX.decode('fda79a24e50ff70ff42f7d89585da5bd19d9e5cc'),
        );
        const b = a.clone();

        expect(a).toEqual(b);
        expect(a).not.toBe(b);
    });

    test('comparisonCloneEqualTo', () => {
        const a = new VersionedChecksummedBytes(
            testParams.getAddressHeader(),
            Utils.HEX.decode('fda79a24e50ff70ff42f7d89585da5bd19d9e5cc'),
        );
        const b = a.clone();

        expect(a.compareTo(b)).toBe(0);
    });
});
