
import { Buffer } from 'buffer';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { BitcoinSerializer } from '../../src/net/bigtangle/core/BitcoinSerializer';
import { Transaction } from '../../src/net/bigtangle/core/Transaction';
import { HeadersMessage } from '../../src/net/bigtangle/core/HeadersMessage';
import { Block } from '../../src/net/bigtangle/core/Block';
import { Utils } from '../../src/net/bigtangle/utils/Utils';
import { ProtocolException } from '../../src/net/bigtangle/exception/ProtocolException';

describe('BitcoinSerializerTest', () => {
    const ADDRESS_MESSAGE_BYTES = Buffer.from(
        'f9beb4d96164647200000000000000001f000000' +
        'ed52399b01e215104d010000000000000000000000000000000000ffff0a000001208d',
        'hex',
    );

    const TRANSACTION_MESSAGE_BYTES = Buffer.from(
        'f9beb4d9747800000000000000000000' +
        '02010000e293cdbe01000000016dbddb' +
        '085b1d8af75184f0bc01fad58d1266e9' +
        'b63b50881990e4b40d6aee3629000000' +
        '008b483045022100f3581e1972ae8ac7' +
        'c7367a7a253bc1135223adb9a468bb3a' +
        '59233f45bc578380022059af01ca17d0' +
        '0e41837a1d58e97aa31bae584edec28d' +
        '35bd96923690913bae9a0141049c02bf' +
        'c97ef236ce6d8fe5d94013c721e91598' +
        '2acd2b12b65d9b7d59e20a842005f8fc' +
        '4e02532e873d37b96f09d6d4511ada8f' +
        '14042f46614a4c70c0f14beff5ffffff' +
        'ff02404b4c00000000001976a9141aa0' +
        'cd1cbea6e7458a7abad512a9d9ea1afb' +
        '225e88ac80fae9c7000000001976a914' +
        '0eab5bea436a0484cfab12485efda0b7' +
        '8b4e' +
        'cc5288ac00000000',
        'hex',
    );

    // TODO new binary @Test
    test.skip('testCachedParsing', () => {
        const serializer = MainNetParams.get().getSerializer(true);

        let transaction = serializer.deserialize(ByteBuffer.wrap(TRANSACTION_MESSAGE_BYTES)) as Transaction;
        expect(transaction).not.toBeNull();
        expect(transaction.isCached()).toBe(true);

        transaction.setLockTime(1);
        expect(transaction.isCached()).toBe(false);
        expect(transaction.getInputs()[0].isCached()).toBe(true);

        let bos = new ByteArrayOutputStream();
        serializer.serialize(transaction, bos);
        expect(Buffer.compare(TRANSACTION_MESSAGE_BYTES, bos.toByteArray())).not.toBe(0);

        transaction = serializer.deserialize(ByteBuffer.wrap(TRANSACTION_MESSAGE_BYTES)) as Transaction;
        expect(transaction).not.toBeNull();
        expect(transaction.isCached()).toBe(true);

        transaction.getInputs()[0].setSequenceNumber(1);
        expect(transaction.isCached()).toBe(false);
        expect(transaction.getInputs()[0].isCached()).toBe(false);

        bos = new ByteArrayOutputStream();
        serializer.serialize(transaction, bos);
        expect(Buffer.compare(TRANSACTION_MESSAGE_BYTES, bos.toByteArray())).not.toBe(0);

        transaction = serializer.deserialize(ByteBuffer.wrap(TRANSACTION_MESSAGE_BYTES)) as Transaction;
        expect(transaction).not.toBeNull();
        expect(transaction.isCached()).toBe(true);
        bos = new ByteArrayOutputStream();
        serializer.serialize(transaction, bos);
        expect(Buffer.compare(TRANSACTION_MESSAGE_BYTES, bos.toByteArray())).toBe(0);

        transaction = serializer.deserialize(ByteBuffer.wrap(TRANSACTION_MESSAGE_BYTES)) as Transaction;
        expect(transaction).not.toBeNull();
        expect(transaction.isCached()).toBe(true);

        transaction.getInputs()[0].setSequenceNumber(transaction.getInputs()[0].getSequenceNumber());

        bos = new ByteArrayOutputStream();
        serializer.serialize(transaction, bos);
        expect(Buffer.compare(TRANSACTION_MESSAGE_BYTES, bos.toByteArray())).toBe(0);
    });

    // Binary is incompatible @Test
    test.skip('testHeaders1', () => {
        const serializer = MainNetParams.get().getDefaultSerializer();

        const headersMessageBytes = Buffer.from(
            'f9beb4d9686561' +
            '646572730000000000520000005d4fab8101010000006fe28c0ab6f1b372c1a6a246ae6' +
            '3f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677b' +
            'a1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d01e3629900',
            'hex',
        );
        const headersMessage = serializer.deserialize(headersMessageBytes) as HeadersMessage;

        const block = headersMessage.getBlockHeaders()[0];
        expect(block.getHashAsString()).toBe('00000000839a8e6886ab5951d76f411475428afc90947ee320161bbf18eb6048');
        expect(block.transactions).not.toBeNull();
        expect(Utils.HEX.encode(block.getMerkleRoot().getBytes())).toBe(
            '0e3e2357e806b6cdb1f70b54c3a3a17b6714ee1f0e68bebb44a74b1efd512098',
        );

        const byteArrayOutputStream = new ByteArrayOutputStream();
        serializer.serialize(headersMessage, byteArrayOutputStream);
        const serializedBytes = byteArrayOutputStream.toByteArray();
        expect(Buffer.compare(headersMessageBytes, serializedBytes)).toBe(0);
    });

    // Binary is incompatible @Test
    test.skip('testHeaders2', () => {
        const serializer = MainNetParams.get().getDefaultSerializer();

        const headersMessageBytes = Buffer.from(
            'f9beb4d96865616465' +
            '72730000000000e701000085acd4ea06010000006fe28c0ab6f1b372c1a6a246ae63f74f931e' +
            '8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1c' +
            'db606e857233e0e61bc6649ffff001d01e3629900010000004860eb18bf1b1620e37e9490fc8a' +
            '427514416fd75159ab86688e9a8300000000d5fdcc541e25de1c7a5addedf24858b8bb665c9f36' +
            'ef744ee42c316022c90f9bb0bc6649ffff001d08d2bd610001000000bddd99ccfda39da1b108ce1' +
            'a5d70038d0a967bacb68b6b63065f626a0000000044f672226090d85db9a9f2fbfe5f0f9609b387' +
            'af7be5b7fbb7a1767c831c9e995dbe6649ffff001d05e0ed6d00010000004944469562ae1c2c74' +
            'd9a535e00b6f3e40ffbad4f2fda3895501b582000000007a06ea98cd40ba2e3288262b28638cec' +
            '5337c1456aaf5eedc8e9e5a20f062bdf8cc16649ffff001d2bfee0a9000100000085144a84488e' +
            'a88d221c8bd6c059da090e88f8a2c99690ee55dbba4e00000000e11c48fecdd9e72510ca84f023' +
            '370c9a38bf91ac5cae88019bee94d24528526344c36649ffff001d1d03e4770001000000fc33f5' +
            '96f822a0a1951ffdbf2a897b095636ad871707bf5d3162729b00000000379dfb96a5ea8c81700ea4' +
            'ac6b97ae9a9312b2d4301a29580e924ee6761a2520adc46649ffff001d189c4c9700',
            'hex',
        );
        const headersMessage = serializer.deserialize(headersMessageBytes) as HeadersMessage;

        expect(headersMessage.getBlockHeaders().length).toBe(6);

        const zeroBlock = headersMessage.getBlockHeaders()[0];
        expect(zeroBlock.getHashAsString()).toBe('00000000839a8e6886ab5951d76f411475428afc90947ee320161bbf18eb6048');
        expect(zeroBlock.getNonce()).toBe(2573394689);

        const thirdBlock = headersMessage.getBlockHeaders()[3];
        expect(thirdBlock.getHashAsString()).toBe('000000004ebadb55ee9096c9a2f8880e09da59c0d68b1c228da88e48844a1485');
        expect(thirdBlock.getNonce()).toBe(2850094635);

        const byteArrayOutputStream = new ByteArrayOutputStream();
        serializer.serialize(headersMessage, byteArrayOutputStream);
        const serializedBytes = byteArrayOutputStream.toByteArray();
        expect(Buffer.compare(headersMessageBytes, serializedBytes)).toBe(0);
    });

    test('testBitcoinPacketHeaderTooShort', () => {
        expect(() => {
            new BitcoinSerializer.BitcoinPacketHeader(Buffer.from([0]));
        }).toThrow();
    });

    test('testBitcoinPacketHeaderTooLong', () => {
        expect(() => {
            const wrongMessageLength = Buffer.from('000000000000000000000000010000020000000000', 'hex');
            new BitcoinSerializer.BitcoinPacketHeader(wrongMessageLength);
        }).toThrow(ProtocolException);
    });

    test('testSeekPastMagicBytes', () => {
        expect(() => {
            const brokenMessage = Buffer.from('000000', 'hex');
            MainNetParams.get().getDefaultSerializer().seekPastMagicBytes(brokenMessage);
        }).toThrow();
    });
});
