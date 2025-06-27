
import { Buffer } from 'buffer';
import { Base58 } from '../../src/net/bigtangle/utils/Base58';
import { AddressFormatException } from '../../src/net/bigtangle/exception';

describe('Base58Test', () => {
    test('testEncode', () => {
        const testbytes = Buffer.from('Hello World');
        expect(Base58.encode(testbytes)).toBe('JxF12TrwUP45BMd');

        const bi = BigInt(3471844090);
        const biBytes = Buffer.from(bi.toString(16), 'hex');
        expect(Base58.encode(biBytes)).toBe('16Ho7Hs');

        const zeroBytes1 = Buffer.alloc(1);
        expect(Base58.encode(zeroBytes1)).toBe('1');

        const zeroBytes7 = Buffer.alloc(7);
        expect(Base58.encode(zeroBytes7)).toBe('1111111');

        // test empty encode
        expect(Base58.encode(Buffer.from([]))).toBe('');
    });

    test('testDecode', () => {
        const testbytes = Buffer.from('Hello World');
        const actualbytes = Base58.decode('JxF12TrwUP45BMd');
        expect(Buffer.compare(testbytes, actualbytes)).toBe(0);

        expect(Buffer.compare(Base58.decode('1'), Buffer.alloc(1))).toBe(0);
        expect(Buffer.compare(Base58.decode('1111'), Buffer.alloc(4))).toBe(0);

        try {
            Base58.decode("This isn't valid base58");
            fail();
        } catch (e) {
            expect(e).toBeInstanceOf(AddressFormatException);
        }

        Base58.decodeChecked('4stwEBjT6FYyVV');

        // Checksum should fail.
        try {
            Base58.decodeChecked('4stwEBjT6FYyVW');
            fail();
        } catch (e) {
            expect(e).toBeInstanceOf(AddressFormatException);
        }

        // Input is too short.
        try {
            Base58.decodeChecked('4s');
            fail();
        } catch (e) {
            expect(e).toBeInstanceOf(AddressFormatException);
        }

        // Test decode of empty String.
        expect(Base58.decode('').length).toBe(0);

        // Now check we can correctly decode the case where the high bit of the first
        // byte is not zero, so BigInteger
        // sign extends. Fix for a bug that stopped us parsing keys exported using sipas
        // patch.
        Base58.decodeChecked('93VYUMzRG9DdbRP72uQXjaWibbQwygnvaCu9DumcqDjGybD864T');
    });

    test('testDecodeToBigInteger', () => {
        const input = Base58.decode('129');
        expect(Base58.decodeToBigInteger('129')).toBe(
            BigInt('0x' + input.toString('hex')),
        );
    });
});
