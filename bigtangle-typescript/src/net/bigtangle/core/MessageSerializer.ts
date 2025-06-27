import { Message } from './Message';
import { Block } from './Block';
import { Transaction } from './Transaction';
import { AlertMessage } from './AlertMessage';
import { BloomFilter } from './BloomFilter';
import { NetworkParameters } from './NetworkParameters';
import { ProtocolException } from '../exception/Exceptions';
import { Gzip } from '../utils/Gzip';
import { Buffer } from 'buffer';

/**
 * Generic interface for classes which serialize/deserialize messages.
 * Implementing classes should be immutable.
 */
export abstract class MessageSerializer {
    protected params: NetworkParameters;
    protected parseRetain: boolean;

    constructor(params: NetworkParameters, parseRetain: boolean) {
        this.params = params;
        this.parseRetain = parseRetain;
    }

    /**
     * Reads a message from the given ByteBuffer and returns it.
     */
    public abstract deserialize(inBuffer: Buffer): Message;

    /**
     * Whether the serializer will produce cached mode Messages
     */
    public isParseRetainMode(): boolean {
        return this.parseRetain;
    }

    /**
     * Make an alert message from the payload. Extension point for alternative
     * serialization format support.
     */
    public abstract makeAlertMessage(payloadBytes: Buffer): AlertMessage;

    /**
     * Make a block from the payload, using an offset of zero and the payload length
     * as block length.
     */
    public makeBlock(payloadBytes: Buffer): Block;
    /**
     * Make a block from the payload, using an offset of zero and the provided
     * length as block length.
     */
    public makeBlock(payloadBytes: Buffer, length: number): Block;
    /**
     * Make a block from the payload, using an offset of zero and the provided
     * length as block length. Extension point for alternative serialization format
     * support.
     */
    public abstract makeBlock(payloadBytes: Buffer, offset: number, length: number): Block;
    public makeBlock(...args: any[]): Block {
        if (args.length === 1) {
            return this.makeBlock(args[0], 0, args[0].length);
        } else if (args.length === 2) {
            return this.makeBlock(args[0], 0, args[1]);
        } else {
            throw new Error("Not implemented");
        }
    }

    /**
     * Make a block from the zipped payload, using an offset of zero and the payload
     * length as block length.
     * 
     * @throws IOException
     */
    public makeZippedBlock(payloadBytes: Buffer): Block {
        const unzipped = Gzip.decompress(payloadBytes);
        return this.makeBlock(unzipped, 0, unzipped.length);
    }

    /**
     * Make a block from the zipped payload, using an offset of zero and the payload
     * length as block length.
     * 
     * @throws IOException
     */
    public makeZippedBlockStream(inputStream: any): Block | null {
        if (inputStream === null) {
            return null; // Return null if no value available.
        }
        const unzipped = Gzip.decompressStream(inputStream);
        return this.makeBlock(unzipped, 0, unzipped.length);
    }

    /**
     * Make an filter message from the payload. Extension point for alternative
     * serialization format support.
     */
    public abstract makeBloomFilter(payloadBytes: Buffer): BloomFilter;

    /**
     * Make a transaction from the payload. Extension point for alternative
     * serialization format support.
     * 
     * @throws UnsupportedOperationException if this serializer/deserializer does
     *                                       not support deserialization. This can
     *                                       occur either because it's a dummy
     *                                       serializer (i.e. for messages with no
     *                                       network parameters), or because it does
     *                                       not support deserializing transactions.
     */
    public abstract makeTransaction(payloadBytes: Buffer, offset: number, length: number, hash: Buffer | null): Transaction;

    /**
     * Make a transaction from the payload. Extension point for alternative
     * serialization format support.
     * 
     * @throws UnsupportedOperationException if this serializer/deserializer does
     *                                       not support deserialization. This can
     *                                       occur either because it's a dummy
     *                                       serializer (i.e. for messages with no
     *                                       network parameters), or because it does
     *                                       not support deserializing transactions.
     */
    public makeTransactionFromBytes(payloadBytes: Buffer): Transaction;
    public makeTransactionFromBytes(payloadBytes: Buffer, offset: number): Transaction;
    public makeTransactionFromBytes(...args: any[]): Transaction {
        if (args.length === 1) {
            return this.makeTransaction(args[0], 0, args[0].length, null);
        } else if (args.length === 2) {
            return this.makeTransaction(args[0], args[1], args[0].length - args[1], null);
        } else {
            throw new Error("Not implemented");
        }
    }

    public abstract seekPastMagicBytes(inBuffer: Buffer): Buffer;

    /**
     * Writes message to to the output stream.
     * 
     * @throws UnsupportedOperationException if this serializer/deserializer does
     *                                       not support serialization. This can
     *                                       occur either because it's a dummy
     *                                       serializer (i.e. for messages with no
     *                                       network parameters), or because it does
     *                                       not support serializing the given
     *                                       message.
     */
    public abstract serialize(name: string, message: Buffer, out: any): void;

    /**
     * Writes message to to the output stream.
     * 
     * @throws UnsupportedOperationException if this serializer/deserializer does
     *                                       not support serialization. This can
     *                                       occur either because it's a dummy
     *                                       serializer (i.e. for messages with no
     *                                       network parameters), or because it does
     *                                       not support serializing the given
     *                                       message.
     */
    public abstract serializeMessage(message: Message, out: any): void;
}