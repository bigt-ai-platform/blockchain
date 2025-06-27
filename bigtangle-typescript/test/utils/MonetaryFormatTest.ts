
import { Coin } from '../../src/net/bigtangle/core/Coin';
import { MonetaryFormat } from '../../src/net/bigtangle/utils/MonetaryFormat';
import { NetworkParameters } from '../../src/net/bigtangle/core/NetworkParameters';

describe('MonetaryFormatTest', () => {
    const NO_CODE = MonetaryFormat.FIAT.noCode();

    test('testSigns', () => {
        expect(NO_CODE.format(Coin.COIN.negate()).toString()).toBe('-1');
        expect(
            NO_CODE.negativeSign('@')
                .format(Coin.COIN.divide(100).negate())
                .toString(),
        ).toBe('@0.01');
        expect(NO_CODE.format(Coin.COIN).toString()).toBe('1');
        expect(NO_CODE.positiveSign('+').format(Coin.COIN).toString()).toBe('+1');
    });

    test('testDecimalMark', () => {
        expect(
            NO_CODE.decimalMark(',').format(Coin.COIN.divide(100)).toString(),
        ).toBe('0,01');
    });

    test('testGrouping', () => {
        expect(format(NO_CODE.parse('0.1'), 0, 1, 2, 3)).toBe('0.1');
        expect(format(NO_CODE.parse('0.01'), 0, 1, 2, 3)).toBe('0.010');
        expect(format(NO_CODE.parse('0.001'), 0, 1, 2, 3)).toBe('0.001');
        expect(format(NO_CODE.parse('0.0001'), 0, 1, 2, 3)).toBe('0.000100');
        expect(format(NO_CODE.parse('0.00001'), 0, 1, 2, 3)).toBe('0.000010');
        expect(format(NO_CODE.parse('0.000001'), 0, 1, 2, 3)).toBe('0.000001');
    });

    test('testTooSmall', () => {
        expect(() => {
            format(NO_CODE.parse('0.0000001'), 0, 1, 2, 3);
        }).toThrow();
    });

    test('btcRounding', () => {
        expect(format(Coin.ZERO, 0, 0)).toBe('0');
        expect(format(Coin.COIN, 0, 0)).toBe('1');
    });

    function format(
        coin: Coin,
        shift: number,
        minDecimals: number,
        ...decimalGroups: number[]
    ): string {
        return NO_CODE.shift(shift)
            .minDecimals(minDecimals)
            .optionalDecimals(...decimalGroups)
            .format(coin)
            .toString();
    }

    test('parse', () => {
        expect(NO_CODE.parse('1')).toEqual(Coin.COIN);
        expect(NO_CODE.parse('1.')).toEqual(Coin.COIN);
        expect(NO_CODE.parse('1.0')).toEqual(Coin.COIN);
        expect(NO_CODE.decimalMark(',').parse('1,0')).toEqual(Coin.COIN);
        expect(NO_CODE.parse('01.0000000000')).toEqual(Coin.COIN);
        expect(NO_CODE.positiveSign('+').parse('+1.0')).toEqual(Coin.COIN);
        expect(NO_CODE.parse('-1')).toEqual(Coin.COIN.negate());
        expect(NO_CODE.parse('-1.0')).toEqual(Coin.COIN.negate());
        expect(NO_CODE.parse('.01')).toEqual(Coin.COIN.divide(100));
    });

    test('parseInvalidEmpty', () => {
        expect(() => {
            NO_CODE.parse('');
        }).toThrow();
    });

    test('parseInvalidWhitespaceSign', () => {
        expect(() => {
            NO_CODE.parse('- 1');
        }).toThrow();
    });

    test('parseInvalidMultipleDecimalMarks', () => {
        expect(() => {
            NO_CODE.parse('1.0.0');
        }).toThrow();
    });

    test('parseInvalidDecimalMark', () => {
        expect(() => {
            NO_CODE.decimalMark(',').parse('1.0');
        }).toThrow();
    });

    test('parseInvalidPositiveSign', () => {
        expect(() => {
            NO_CODE.positiveSign('@').parse('+1.0');
        }).toThrow();
    });

    test('parseInvalidNegativeSign', () => {
        expect(() => {
            NO_CODE.negativeSign('@').parse('-1.0');
        }).toThrow();
    });
});
