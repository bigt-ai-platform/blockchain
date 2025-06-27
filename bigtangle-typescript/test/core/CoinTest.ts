
import { Coin } from '../../src/net/bigtangle/core/Coin';
import { NetworkParameters } from '../../src/net/bigtangle/core/NetworkParameters';
import { MonetaryFormat } from '../../src/net/bigtangle/utils/MonetaryFormat';
import { OrderRecord } from '../../src/net/bigtangle/core/OrderRecord';
import { Token } from '../../src/net/bigtangle/core/Token';

describe('CoinTest', () => {
    test('testParseCoin', () => {
        // String version
        expect(MonetaryFormat.FIAT.noCode().parse('0.01')).toEqual(
            Coin.COIN.divide(100),
        );
        expect(MonetaryFormat.FIAT.noCode().parse('1.01')).toEqual(
            Coin.COIN.add(Coin.COIN.divide(100)),
        );
        expect(MonetaryFormat.FIAT.noCode().parse('-1')).toEqual(Coin.COIN.negate());
        try {
            MonetaryFormat.FIAT.noCode().parse('2E-20');
            fail('should not have accepted fractional satoshis');
        } catch (e) {
            expect(e).toBeInstanceOf(Error);
        }
    });

    test('testValueOf', () => {
        Coin.valueOf(BigInt(Number.MAX_SAFE_INTEGER), NetworkParameters.BIGTANGLE_TOKENID);
        Coin.valueOf(BigInt(Number.MIN_SAFE_INTEGER), NetworkParameters.BIGTANGLE_TOKENID);
    });

    test('testOperators', () => {
        expect(Coin.ZERO.isPositive()).toBe(false);
        expect(Coin.ZERO.isNegative()).toBe(false);
        expect(Coin.ZERO.isZero()).toBe(true);

        expect(
            Coin.valueOf(
                BigInt(2),
                NetworkParameters.BIGTANGLE_TOKENID,
            ).isGreaterThan(
                Coin.valueOf(BigInt(1), NetworkParameters.BIGTANGLE_TOKENID),
            ),
        ).toBe(true);
        expect(
            Coin.valueOf(
                BigInt(2),
                NetworkParameters.BIGTANGLE_TOKENID,
            ).isGreaterThan(
                Coin.valueOf(BigInt(2), NetworkParameters.BIGTANGLE_TOKENID),
            ),
        ).toBe(false);
        expect(
            Coin.valueOf(
                BigInt(1),
                NetworkParameters.BIGTANGLE_TOKENID,
            ).isGreaterThan(
                Coin.valueOf(BigInt(2), NetworkParameters.BIGTANGLE_TOKENID),
            ),
        ).toBe(false);
        expect(
            Coin.valueOf(
                BigInt(1),
                NetworkParameters.BIGTANGLE_TOKENID,
            ).isLessThan(Coin.valueOf(BigInt(2), NetworkParameters.BIGTANGLE_TOKENID)),
        ).toBe(true);
        expect(
            Coin.valueOf(
                BigInt(2),
                NetworkParameters.BIGTANGLE_TOKENID,
            ).isLessThan(Coin.valueOf(BigInt(2), NetworkParameters.BIGTANGLE_TOKENID)),
        ).toBe(false);
        expect(
            Coin.valueOf(
                BigInt(2),
                NetworkParameters.BIGTANGLE_TOKENID,
            ).isLessThan(Coin.valueOf(BigInt(1), NetworkParameters.BIGTANGLE_TOKENID)),
        ).toBe(false);
    });

    test('testMultiplicationOverflow', () => {
        expect(() => {
            Coin.valueOf(
                BigInt(Number.MAX_SAFE_INTEGER),
                NetworkParameters.BIGTANGLE_TOKENID,
            ).multiply(2);
        }).toThrow();
    });

    test('testMultiplicationUnderflow', () => {
        expect(() => {
            Coin.valueOf(
                BigInt(Number.MIN_SAFE_INTEGER),
                NetworkParameters.BIGTANGLE_TOKENID,
            ).multiply(2);
        }).toThrow();
    });

    test('testAdditionOverflow', () => {
        expect(() => {
            Coin.valueOf(
                BigInt(Number.MAX_SAFE_INTEGER),
                NetworkParameters.BIGTANGLE_TOKENID,
            ).add(Coin.COIN);
        }).toThrow();
    });

    test('testSubstractionUnderflow', () => {
        expect(() => {
            Coin.valueOf(
                BigInt(Number.MIN_SAFE_INTEGER),
                NetworkParameters.BIGTANGLE_TOKENID,
            ).subtract(Coin.COIN);
        }).toThrow();
    });

    test('testToPlainString', () => {
        const format = MonetaryFormat.FIAT.noCode();
        expect(
            format.format(
                Coin.valueOf(BigInt(150000), NetworkParameters.BIGTANGLE_TOKENID),
            ),
        ).toBe('0.15');
        expect(format.format(format.parse('1.23'))).toBe('1.23');

        expect(format.format(format.parse('0.1'))).toBe('0.1');
        expect(format.format(format.parse('1.1'))).toBe('1.1');
        expect(format.format(format.parse('21.12'))).toBe('21.12');
        expect(format.format(format.parse('321.123'))).toBe('321.123');
        expect(format.format(format.parse('4321.1234'))).toBe('4321.1234');
        expect(format.format(format.parse('54321.12345'))).toBe('54321.12345');
        expect(format.format(format.parse('654321.123456'))).toBe('654321.123456');

        // check there are no trailing zeros
        expect(format.format(format.parse('1.0'))).toBe('1');
        expect(format.format(format.parse('2.00'))).toBe('2');
        expect(format.format(format.parse('3.000'))).toBe('3');
        expect(format.format(format.parse('4.0000'))).toBe('4');
        expect(format.format(format.parse('5.00000'))).toBe('5');
        expect(format.format(format.parse('6.000000'))).toBe('6');
        expect(format.format(format.parse('7.0000000'))).toBe('7');
    });

    test('testToPlainStringFail', () => {
        expect(() => {
            const format = MonetaryFormat.FIAT.noCode();
            format.format(format.parse('7654321.1234567'));
            format.format(format.parse('87654321.12345678'));
        }).toThrow();
    });

    test('testOrder', () => {
        const mf = MonetaryFormat.FIAT.noCode();
        const orderRecord = new OrderRecord();
        orderRecord.setOfferValue(BigInt(48));
        orderRecord.setTargetValue(BigInt(24));
        orderRecord.setOfferTokenid(NetworkParameters.BIGTANGLE_TOKENID_STRING);
        // "SELL"
        const t = new Token();
        t.setDecimals(2);
        expect(mf.format(orderRecord.getOfferValue(), t.getDecimals())).toBe('0.48');
        expect(
            mf.format(
                (orderRecord.getTargetValue() * BigInt(10 ** t.getDecimals())) /
                    orderRecord.getOfferValue(),
            ),
        ).toBe('0.00005');
    });
});
