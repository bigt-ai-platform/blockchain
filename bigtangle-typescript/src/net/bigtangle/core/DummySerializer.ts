import { MessageSerializer } from './MessageSerializer';
import { Message } from './Message';
import { Block } from './Block';
import { Transaction } from './Transaction';
import { AlertMessage } from './AlertMessage';
import { BloomFilter } from './BloomFilter';
import { Buffer } from 'buffer';

/**
 * Dummy serializer used ONLY for objects which do not have network parameters
 * set.
 */
export class DummySerializer extends MessageSerializer {
    public serializeMessage(message: Message, out: any): void {
        throw new Error(DummySerializer.DEFAULT_EXCEPTION_MESSAGE);
    }
    public static readonly DEFAULT = new DummySerializer();

    private static readonly DEFAULT_EXCEPTION_MESSAGE = "Dummy serializer cannot serialize/deserialize objects as it does not know which network they belong to.";

    constructor() {
        super(null as any, false); // Pass null for params and false for parseRetain as it's a dummy
    }

    deserialize(inBuffer: Buffer): Message {
        throw new Error(DummySerializer.DEFAULT_EXCEPTION_MESSAGE);
    }

    isParseRetainMode(): boolean {
        return false;
    }

    makeAlertMessage(payloadBytes: Buffer): AlertMessage {
        throw new Error(DummySerializer.DEFAULT_EXCEPTION_MESSAGE);
    }

    makeBlock(payloadBytes: Buffer): Block;
    makeBlock(payloadBytes: Buffer, length: number): Block;
    makeBlock(payloadBytes: Buffer, offset: number, length: number): Block;
    makeBlock(payloadBytes: Buffer, arg2?: number, arg3?: number): Block {
        throw new Error(DummySerializer.DEFAULT_EXCEPTION_MESSAGE);
    }

    makeBloomFilter(payloadBytes: Buffer): BloomFilter {
        throw new Error(DummySerializer.DEFAULT_EXCEPTION_MESSAGE);
    }

    makeTransaction(payloadBytes: Buffer, offset: number, length: number, hash: Buffer): Transaction {
        throw new Error(DummySerializer.DEFAULT_EXCEPTION_MESSAGE);
    }

    seekPastMagicBytes(inBuffer: Buffer): Buffer {
        throw new Error(DummySerializer.DEFAULT_EXCEPTION_MESSAGE);
    }

    serialize(message: Message): Buffer;
    serialize(name: string, message: Buffer): Buffer;
    serialize(...args: any[]): Buffer {
        throw new Error(DummySerializer.DEFAULT_EXCEPTION_MESSAGE);
    }
}
