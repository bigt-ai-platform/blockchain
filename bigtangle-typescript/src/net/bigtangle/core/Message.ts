import { NetworkParameters } from './NetworkParameters';
 import { ProtocolVersion } from './ProtocolVersion';
import { ProtocolException } from '../exception/Exceptions';
import { MessageSerializer } from './MessageSerializer';
import { Sha256Hash } from './Sha256Hash';
import { VarInt } from './VarInt';
import { BigInteger } from './BigInteger';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';
import { Utils } from '../utils/Utils';
import { Buffer } from 'buffer';

/**
 * <p>A Message is a data structure that can be serialized/deserialized using the Bitcoin serialization format.
 * Specific types of messages that are used both in the block chain, and on the wire, are derived from this
 * class.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
export abstract class Message {
    public static readonly MAX_SIZE = 0x02000000; // 32MB
    public static readonly UNKNOWN_LENGTH = -1;

    // The offset is how many bytes into the provided byte array this message payload starts at.
    protected offset: number = 0;
    // The cursor keeps track of where we are in the byte array as we parse it.
    // Note that it's relative to the start of the array NOT the start of the message payload.
    protected cursor: number = 0;

    public length: number = Message.UNKNOWN_LENGTH;

    // The raw message payload bytes themselves.
    protected payload: Buffer;

    protected recached: boolean = false;
    protected serializer: MessageSerializer;

    protected protocolVersion: number;

    protected params: NetworkParameters;

    constructor(params: NetworkParameters, payload?: Buffer, offset?: number, serializer?: MessageSerializer, length?: number) {
        this.params = params;
        this.serializer = serializer || params.getDefaultSerializer();
        this.payload = payload || Buffer.alloc(0);
        // Set protocolVersion from params or default to 1 if not available
        this.protocolVersion = params.getProtocolVersionNum()

        if (payload) {
            this.parse();

            if (this.length === Message.UNKNOWN_LENGTH) {
                throw new Error(`Length field has not been set in constructor for ${this.constructor.name} after parse.`);
            }
            
            if (!this.serializer.isParseRetainMode()) {
                this.payload = Buffer.alloc(0); // Clear payload if not retaining
            }
        }
    }

    protected abstract parse(): void;

    /**
     * <p>To be called before any change of internal values including any setters. This ensures any cached byte array is
     * removed.<p/>
     * <p>Child messages of this object(e.g. Transactions belonging to a Block) will not have their internal byte caches
     * invalidated unless they are also modified internally.</p>
     */
    public unCache(): void {
        this.payload = Buffer.alloc(0);
        this.recached = false;
    }

    public adjustLength(newArraySize: number, adjustment: number): void {
        if (this.length === Message.UNKNOWN_LENGTH) {
            return;
        }
        // Our own length is now unknown if we have an unknown length adjustment.
        if (adjustment === Message.UNKNOWN_LENGTH) {
            this.length = Message.UNKNOWN_LENGTH;
            return;
        }
        this.length += adjustment;
        // Check if we will need more bytes to encode the length prefix.
        if (newArraySize === 1) {
            this.length++;  // The assumption here is we never call adjustLength with the same arraySize as before.
        } else if (newArraySize !== 0) {
            this.length += VarInt.sizeOf(newArraySize) - VarInt.sizeOf(newArraySize - 1);
        }
    }

    /**
     * used for unit testing
     */
    public isCached(): boolean {
        return this.payload.length > 0;
    }

    public isRecached(): boolean {
        return this.recached;
    }

    /**
     * Returns a copy of the array returned by {@link Message#unsafeBitcoinSerialize()}, which is safe to mutate.
     * If you need extra performance and can guarantee you won't write to the array, you can use the unsafe version.
     *
     * @return a freshly allocated serialized byte array
     */
    public bitcoinSerialize(): Uint8Array {
        const bytes = this.unsafeBitcoinSerialize();
        const copy = new Uint8Array(bytes.length);
        copy.set(bytes);
        return copy;
    }

    /**
     * Serialize this message to a byte array that conforms to the bitcoin wire protocol.
     * <br/>
     * This method may return the original byte array used to construct this message if the
     * following conditions are met:
     * <ol>
     * <li>1) The message was parsed from a byte array with parseRetain = true</li>
     * <li>2) The message has not been modified</li>
     * <li>3) The array had an offset of 0 and no surplus bytes</li>
     * </ol>
     *
     * If condition 3 is not met then an copy of the relevant portion of the array will be returned.
     * Otherwise a full serialize will occur. For this reason you should only use this API if you can guarantee you
     * will treat the resulting array as read only.
     *
     * @return a byte array owned by this object, do NOT mutate it.
     */
    public unsafeBitcoinSerialize(): Uint8Array {
        // 1st attempt to use a cached array.
        if (this.payload.length > 0) {
            if (this.offset === 0 && this.length === this.payload.length) {
                // Cached byte array is the entire message with no extras so we can return as is and avoid an array
                // copy.
                return this.payload;
            }

            const buf = new Uint8Array(this.length);
            this.payload.copy(buf, 0, this.offset, this.offset + this.length);
            return buf;
        }

        // No cached array available so serialize parts by stream.
        const stream = new UnsafeByteArrayOutputStream(this.length < 32 ? 32 : this.length + 32);
        this.bitcoinSerializeToStream(stream);

        if (this.serializer.isParseRetainMode()) {
            this.payload = Buffer.from(stream.toByteArray());
            this.cursor = this.cursor - this.offset;
            this.offset = 0;
            this.recached = true;
            this.length = this.payload.length;
            return this.payload;
        }
        const buf = stream.toByteArray();
        this.length = buf.length;
        return buf;
    }

 
    /**
     * Serializes this message to the provided stream. If you just want the raw bytes use bitcoinSerialize().
     */
    protected bitcoinSerializeToStream(stream: any): void {
        console.error(`Error: ${this.constructor.name} class has not implemented bitcoinSerializeToStream method.  Generating message with no payload`);
    }

    /**
     * This method is a NOP for all classes except Block and Transaction.  It is only declared in Message
     * so BitcoinSerializer can avoid 2 instanceof checks + a casting.
     */
    public getHash(): Sha256Hash {
        throw new Error("UnsupportedOperationException");
    }

    /**
     * This returns a correct value by parsing the message.
     */
    public  getMessageSize(): number {
        if (this.length === Message.UNKNOWN_LENGTH) {
            throw new Error(`Length field has not been set in ${this.constructor.name}.`);
        }
        return this.length;
    }

    protected readUint32(): number {
        try {
            const u = this.payload.readUInt32LE(this.cursor);
            this.cursor += 4;
            return u;
        } catch (e: any) {
            throw new ProtocolException(e);
        }
    }

    protected readInt64(): number {
        try {
            // JavaScript numbers are 64-bit floats, so direct conversion might lose precision for large integers.
            // For full 64-bit integer support, a library like 'long.js' or BigInt would be needed.
            const low = this.payload.readUInt32LE(this.cursor);
            const high = this.payload.readUInt32LE(this.cursor + 4);
            this.cursor += 8;
            // Combine low and high parts. This is a simplified representation.
            // For actual 64-bit integers, use BigInt or a dedicated library.
            return low + high * Math.pow(2, 32);
        } catch (e: any) {
            throw new ProtocolException(e);
        }
    }

    protected readUint64(): BigInteger {
        // Java does not have an unsigned 64 bit type. So scrape it off the wire then flip.
        // In TypeScript, we can use BigInt directly.
        const bytes = this.readBytes(8);
        // Assuming little-endian for readUint64 based on common Bitcoin protocol practices
        const value = new BigInteger(Utils.HEX.encode(bytes.reverse()), 16); // Read as hex, then convert to BigInteger
        return value;
    }

    protected readVarInt(): number {
        const varint = VarInt.read(this.payload, this.cursor);
        this.cursor += varint.size;
        return varint.value;
    }

    protected readBytes(length: number): Buffer {
        if (length > Message.MAX_SIZE) {
            throw new ProtocolException(`Claimed value length too large: ${length}`);
        }
        try {
            const b = this.payload.subarray(this.cursor, this.cursor + length);
            this.cursor += length;
            return b;
        } catch (e: any) {
            throw new ProtocolException(e);
        }
    }
    
    protected readByteArray(): Buffer {
        const len = this.readVarInt();
        return this.readBytes(len);
    }

    protected readStr(): string {
        const length = this.readVarInt();
        const bytes = this.readBytes(length);
        return new TextDecoder('utf-8').decode(bytes);
    }

    protected readHash(): Sha256Hash {
        // We have to flip it around, as it's been read off the wire in little endian.
        // Not the most efficient way to do this but the clearest.
        const bytes = this.readBytes(32);
        return Sha256Hash.wrapReversed(bytes);
    }

    protected hasMoreBytes(): boolean {
        return this.cursor < this.payload.length;
    }

    /** Network parameters this message was created with. */
    public getParams(): NetworkParameters {
        return this.params;
    }
}