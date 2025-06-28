import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { DumpedPrivateKey } from '../../src/net/bigtangle/utils/DumpedPrivateKey';
import { ECKey } from '../../src/net/bigtangle/core/ECKey';
import { BigInteger } from '../../src/net/bigtangle/core/BigInteger';

describe('DumpedPrivateKeyTest', () => {
    const MAINNET = MainNetParams.get();

    test('checkNetwork', () => {
        DumpedPrivateKey.fromBase58(
            MAINNET,
            '5HtUCLMFWNueqN9unpgX2DzjMg6SDNZyKRb8s3LJgpFg5ubuMrk',
        );
    });

    test('cloning', () => {
        const a = new DumpedPrivateKey(MAINNET, ECKey.fromPrivate(new BigInteger('1')).getPrivKeyBytes(), true);
        const b = a.clone();

        expect(a).toEqual(b);
        expect(a).not.toBe(b);
    });

    test('roundtripBase58', () => {
        const base58 = '5HtUCLMFWNueqN9unpgX2DzjMg6SDNZyKRb8s3LJgpFg5ubuMrk';
        expect(DumpedPrivateKey.fromBase58(null, base58).toBase58()).toBe(base58);
    });
});
