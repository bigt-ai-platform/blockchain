import { MessageSerializer } from './MessageSerializer';
import { NetworkParameters } from './NetworkParameters';
import { ProtocolException } from './exception/Exceptions';
import { Message } from './Message';
import { Block } from './Block';
import { Transaction } from './Transaction';
import { AlertMessage } from './AlertMessage';
import { BloomFilter } from './BloomFilter';
import { Sha256Hash } from './Sha256Hash';
import { Utils } from './Utils';
import { Buffer } from 'buffer';
// We'll use a simple writeable stream interface
interface WriteableStream {
    write(chunk: Buffer): void;
}

// Define BitcoinPacketHeader locally since we removed the separate file
class BitcoinPacketHeader {
    static readonly HEADER_LENGTH = 12 + 4 + 4; // COMMAND_LEN (12) + size (4) + checksum (4)

    constructor(
        public readonly command: string,
        public readonly size: number,
        public readonly checksum: Buffer
    ) {}
}

const COMMAND_LEN = 12;

export class BitcoinSerializer extends MessageSerializer {
    private static readonly names = new Map<Function, string>([
        [Block, "block"],
        [Transaction, "tx"],
        [BloomFilter, "filterload"]
    ]);

    private readonly params: NetworkParameters;
    private readonly parseRetain: boolean;

    constructor(params: NetworkParameters, parseRetain: boolean) {
        super(params, parseRetain);
        this.params = params;
        this.parseRetain = parseRetain;
    }

    serialize(name: string, message: Buffer, out: WriteableStream): void {
        const header = Buffer.alloc(4 + COMMAND_LEN + 4 + 4);
        // Use getPacketMagic() if available, otherwise fallback to constant
        const packetMagic = (this.params as any).getPacketMagic ? 
            (this.params as any).getPacketMagic() : 
            0xf9beb4d9; // Default mainnet magic
        header.writeUInt32BE(packetMagic, 0);

        // Write command name
        for (let i = 0; i < name.length && i < COMMAND_LEN; i++) {
            header[4 + i] = name.charCodeAt(i) & 0xFF;
        }

        // Write message length
        header.writeUInt32LE(message.length, 4 + COMMAND_LEN);

        // Calculate and write checksum
        const hash = Sha256Hash.hashTwice(message);
        const checksum = hash.slice(0, 4);
        // Copy checksum to header
        for (let i = 0; i < 4; i++) {
            header[4 + COMMAND_LEN + 4 + i] = checksum[i];
        }

        // Write header and message
        out.write(header);
        out.write(message);
    }

    serializeMessage(message: Message, out: any): void {
        const name = BitcoinSerializer.names.get(message.constructor);
        if (!name) {
            throw new Error(`BitcoinSerializer doesn't currently know how to serialize ${message.constructor.name}`);
        }
        this.serialize(name, message.bitcoinSerialize(), out);
    }

    deserialize(inBuffer: Buffer): Message {
        const newBuffer = this.seekPastMagicBytes(inBuffer);
        const header = this.deserializeHeader(newBuffer);
        return this.deserializePayload(header, newBuffer.subarray(BitcoinPacketHeader.HEADER_LENGTH));
    }

    deserializePayload(header: BitcoinPacketHeader, inBuffer: Buffer): Message {
        const payloadBytes = inBuffer.subarray(0, header.size);
        // inBuffer position is advanced by header.size
        // No need to reassign inBuffer since we return the message

        // Verify checksum
        const hash = Sha256Hash.hashTwice(payloadBytes);
        const checksum = hash.subarray(0, 4);
        // Compare checksums manually
        let checksumMatch = true;
        for (let i = 0; i < 4; i++) {
            if (checksum[i] !== header.checksum[i]) {
                checksumMatch = false;
                break;
            }
        }
        
        if (!checksumMatch) {
            throw new ProtocolException(`Checksum failed to verify`);
        }

        return this.makeMessage(header.command, header.size, payloadBytes, hash, header.checksum);
    }

    private makeMessage(command: string, length: number, payloadBytes: Buffer, hash: Buffer, checksum: Buffer): Message {
        if (command === "block") {
            return this.makeBlock(payloadBytes, 0, length);
        } else if (command === "tx") {
            return this.makeTransaction(payloadBytes, 0, length, hash);
        } else if (command === "alert") {
            return this.makeAlertMessage(payloadBytes);
        } else if (command === "filterload") {
            return this.makeBloomFilter(payloadBytes);
        } else {
            throw new ProtocolException(`No support for deserializing message with name ${command}`);
        }
    }

    getParameters(): NetworkParameters {
        return this.params;
    }

    makeAlertMessage(payloadBytes: Buffer): AlertMessage {
        return new AlertMessage(this.params, payloadBytes);
    }

    makeBlock(payloadBytes: Buffer, offset: number, length: number): Block {
        return new Block(this.params, payloadBytes, offset, this, length);
    }

    makeTransaction(payloadBytes: Buffer, offset: number, length: number, hash: Buffer): Transaction {
        // Create transaction without setting hash for now
        return new Transaction(this.params, payloadBytes, offset, null, this, length);
    }

    seekPastMagicBytes(inBuffer: Buffer): Buffer {
        let magicCursor = 3;
        let position = 0;
        const magic = (this.params as any).getPacketMagic ? 
            (this.params as any).getPacketMagic() : 
            0xf9beb4d9; // Default mainnet magic
        
        while (position < inBuffer.length) {
            const b = inBuffer[position];
            const expectedByte = (magic >>> (magicCursor * 8)) & 0xFF;
            
            if (b === expectedByte) {
                magicCursor--;
                position++;
                if (magicCursor < 0) {
                    // Found magic bytes, return the buffer starting after magic bytes
                    return inBuffer.subarray(position);
                }
            } else {
                magicCursor = 3;
                position++;
            }
        }
        throw new Error("Magic bytes not found");
    }

    isParseRetainMode(): boolean {
        return this.parseRetain;
    }

    makeBloomFilter(payloadBytes: Buffer): BloomFilter {
        return new BloomFilter(this.params, payloadBytes);
    }

    deserializeHeader(inBuffer: Buffer): BitcoinPacketHeader {
        const header = inBuffer.subarray(0, BitcoinPacketHeader.HEADER_LENGTH);
        // inBuffer position is advanced by HEADER_LENGTH in caller
        // We don't modify the original buffer here

        let cursor = 0;
        let commandEnd = 0;

        // Find command end (null-terminated string)
        for (; commandEnd < COMMAND_LEN && header[commandEnd] !== 0; commandEnd++);
        const command = header.subarray(0, commandEnd).toString('ascii');
        cursor = COMMAND_LEN;

        // Read size
        const size = header.readUInt32LE(cursor);
        cursor += 4;

        const MAX_MESSAGE_SIZE = 10 * 1024 * 1024; // 10MB max message size
        if (size > MAX_MESSAGE_SIZE || size < 0) {
            throw new ProtocolException(`Message size too large: ${size}`);
        }

        // Read checksum
        const checksum = header.subarray(cursor, cursor + 4);
        cursor += 4;

        return new BitcoinPacketHeader(command, size, checksum);
    }
}

// BitcoinPacketHeader is defined at the top of the file
