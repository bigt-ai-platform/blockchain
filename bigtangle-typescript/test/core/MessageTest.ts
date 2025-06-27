
import { Buffer } from 'buffer';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { Message } from '../../src/net/bigtangle/core/Message';
import { ProtocolException } from '../../src/net/bigtangle/exception/ProtocolException';
import { VarInt } from '../../src/net/bigtangle/core/VarInt';

describe('MessageTest', () => {
    // If readStr() is vulnerable this causes OutOfMemory
    test('readStrOfExtremeLength', () => {
        expect(() => {
            const params = MainNetParams.get();
            const length = new VarInt(Number.MAX_SAFE_INTEGER);
            const payload = length.encode();
            new VarStrMessage(params, payload);
        }).toThrow(ProtocolException);
    });

    class VarStrMessage extends Message {
        constructor(params: any, payload: Buffer) {
            super(params, payload, 0);
        }

        protected parse(): void {
            this.readStr();
        }
    }

    // If readBytes() is vulnerable this causes OutOfMemory
    test('readByteArrayOfExtremeLength', () => {
        expect(() => {
            const params = MainNetParams.get();
            const length = new VarInt(Number.MAX_SAFE_INTEGER);
            const payload = length.encode();
            new VarBytesMessage(params, payload);
        }).toThrow(ProtocolException);
    });

    class VarBytesMessage extends Message {
        constructor(params: any, payload: Buffer) {
            super(params, payload, 0);
        }

        protected parse(): void {
            this.readByteArray();
        }
    }
});
