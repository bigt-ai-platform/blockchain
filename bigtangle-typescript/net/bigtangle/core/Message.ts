import { NetworkParameters } from './NetworkParameters';
import { Sha256Hash } from './Sha256Hash';

// Define basic MessageSerializer interface
interface MessageSerializer {
    parseLazy?(): void;
    writeUint32?(value: number): void;
    // Add other required methods as needed
}
import { BigInteger } from 'jsbn';
import { Coin } from './Coin';

export abstract class Message {
    protected params: NetworkParameters;
    protected payload: Buffer;
    protected offset: number;
    protected cursor: number;
    protected length: number;
    protected serializer: any; // Temporarily use any to avoid type issues

    static UNKNOWN_LENGTH = -1;

    constructor(params: NetworkParameters, payloadBytes?: Buffer, offset?: number, serializer?: any, length?: number) {
        this.params = params;
        this.payload = payloadBytes || Buffer.alloc(0);
        this.offset = offset || 0;
        this.cursor = this.offset;
        this.length = length !== undefined ? length : Message.UNKNOWN_LENGTH;
        this.serializer = serializer || null; // No default serializer
    }

    protected abstract parse(payload?: Buffer, offset?: number, length?: number): void;
    protected abstract bitcoinSerializeToStream(stream: any): void; // OutputStream

    bitcoinSerialize(): Uint8Array {
        // Simplified: In a real implementation, this would serialize the message to bytes
        return new Uint8Array(0); // Dummy
    }

    getMessageSize(): number {
        return this.length;
    }

    protected readBytes(length: number): Buffer {
        const bytes = this.payload.slice(this.cursor, this.cursor + length);
        this.cursor += length;
        return bytes;
    }

    protected readByteArray(): Buffer {
        const length = this.readVarInt();
        return this.readBytes(length);
    }

    protected readUint32(): number {
        // Read 4 bytes as little-endian unsigned 32-bit integer
        const val = this.payload[this.cursor] |
                    (this.payload[this.cursor + 1] << 8) |
                    (this.payload[this.cursor + 2] << 16) |
                    (this.payload[this.cursor + 3] << 24);
        this.cursor += 4;
        return val >>> 0; // Convert to unsigned
    }

    protected readCoin(): Coin {
        // Read 8 bytes for value and then variable length for tokenid
        const value = this.readUint64();
        const tokenid = this.readByteArray();
        return new Coin(value, tokenid);
    }

    protected readUint64(): BigInteger {
        // Read 8 bytes as little-endian unsigned 64-bit integer
        // Using jsbn.BigInteger for full 64-bit integer support
        const low = this.payload[this.cursor] |
                    (this.payload[this.cursor + 1] << 8) |
                    (this.payload[this.cursor + 2] << 16) |
                    (this.payload[this.cursor + 3] << 24);
        const high = this.payload[this.cursor + 4] |
                     (this.payload[this.cursor + 5] << 8) |
                     (this.payload[this.cursor + 6] << 16) |
                     (this.payload[this.cursor + 7] << 24);
        this.cursor += 8;
        const lowBigInt = new BigInteger(String(low >>> 0)); // Convert to unsigned
        const highBigInt = new BigInteger(String(high >>> 0)); // Convert to unsigned
        return lowBigInt.add(highBigInt.shiftLeft(32));
    }

    protected readInt64(): number {
        // Simplified: Read 8 bytes as little-endian signed 64-bit integer
        // Note: JavaScript numbers are 64-bit floats, so large integers might lose precision.
        // For full 64-bit integer support, a library like 'long.js' would be needed.
        const low = this.payload[this.cursor] |
                    (this.payload[this.cursor + 1] << 8) |
                    (this.payload[this.cursor + 2] << 16) |
                    (this.payload[this.cursor + 3] << 24);
        const high = this.payload[this.cursor + 4] |
                     (this.payload[this.cursor + 5] << 8) |
                     (this.payload[this.cursor + 6] << 16) |
                     (this.payload[this.cursor + 7] << 24);
        this.cursor += 8;
        // Combine low and high parts. This is a simplified representation.
        // For actual 64-bit integers, use BigInt or a dedicated library.
        return low + high * Math.pow(2, 32);
    }

    protected readHash(): Sha256Hash {
        const hashBytes = this.readBytes(32);
        return Sha256Hash.wrap(hashBytes);
    }

    protected readVarInt(): number {
        // Read a variable-length integer
        const firstByte = this.payload[this.cursor];
        if (firstByte < 0xFD) {
            this.cursor += 1;
            return firstByte;
        } else if (firstByte === 0xFD) {
            this.cursor += 3;
            return this.payload[this.cursor - 2] | (this.payload[this.cursor - 1] << 8);
        } else if (firstByte === 0xFE) {
            this.cursor += 5;
            return this.payload[this.cursor - 4] | (this.payload[this.cursor - 3] << 8) |
                   (this.payload[this.cursor - 2] << 16) | (this.payload[this.cursor - 1] << 24);
        } else { // 0xFF
            this.cursor += 9;
            // This would require BigInt for full 64-bit support
            // For now, return 0 or throw an error for values exceeding 2^32-1
            const val = this.payload[this.cursor - 8] |
                        (this.payload[this.cursor - 7] << 8) |
                        (this.payload[this.cursor - 6] << 16) |
                        (this.payload[this.cursor - 5] << 24);
            if (val > 2**31 -1 || val < -(2**31)) {
                // Handle large numbers, potentially using BigInt or throwing an error
                console.warn("readVarInt: Value exceeds safe integer limit, returning 0.");
                return 0;
            }
            return val;
        }
    }

    protected readStr(): string {
        const length = this.readVarInt();
        const bytes = this.readBytes(length);
        return new TextDecoder('utf-8').decode(bytes);
    }

    protected adjustLength(newTransactionsCount: number, newTransactionLength: number): void {
        // Simplified: Adjust total message length based on new transaction
        this.length = this.length + newTransactionLength; // Very basic adjustment
    }

    protected unCache(): void {
        // No-op for base class
    }
}
