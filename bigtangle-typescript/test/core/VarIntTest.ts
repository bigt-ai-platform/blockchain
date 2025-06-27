
import { VarInt } from '../../src/net/bigtangle/core/VarInt';

describe('VarIntTest', () => {
    test('testBytes', () => {
        const a = new VarInt(10); // with widening conversion
        expect(a.getSizeInBytes()).toBe(1);
        expect(a.encode().length).toBe(1);
        expect(new VarInt(a.encode(), 0).value).toBe(BigInt(10));
    });

    test('testShorts', () => {
        const a = new VarInt(64000); // with widening conversion
        expect(a.getSizeInBytes()).toBe(3);
        expect(a.encode().length).toBe(3);
        expect(new VarInt(a.encode(), 0).value).toBe(BigInt(64000));
    });

    test('testShortFFFF', () => {
        const a = new VarInt(0xffff);
        expect(a.getSizeInBytes()).toBe(3);
        expect(a.encode().length).toBe(3);
        expect(new VarInt(a.encode(), 0).value).toBe(BigInt(0xffff));
    });

    test('testInts', () => {
        const a = new VarInt(0xaabbccdd);
        expect(a.getSizeInBytes()).toBe(5);
        expect(a.encode().length).toBe(5);
        const bytes = a.encode();
        expect(new VarInt(bytes, 0).value).toBe(BigInt(0xaabbccdd));
    });

    test('testIntFFFFFFFF', () => {
        const a = new VarInt(0xffffffff);
        expect(a.getSizeInBytes()).toBe(5);
        expect(a.encode().length).toBe(5);
        const bytes = a.encode();
        expect(new VarInt(bytes, 0).value).toBe(BigInt(0xffffffff));
    });

    test('testLong', () => {
        const a = new VarInt(BigInt('0xCAFEBABEDEADBEEF'));
        expect(a.getSizeInBytes()).toBe(9);
        expect(a.encode().length).toBe(9);
        const bytes = a.encode();
        expect(new VarInt(bytes, 0).value).toBe(BigInt('0xCAFEBABEDEADBEEF'));
    });

    test('testSizeOfNegativeInt', () => {
        // shouldn't normally be passed, but at least stay consistent (bug regression test)
        expect(VarInt.sizeOf(-1)).toBe(new VarInt(-1).encode().length);
    });
});
