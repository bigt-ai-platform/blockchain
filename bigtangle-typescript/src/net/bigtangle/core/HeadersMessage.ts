import { Message } from './Message';
import { Block } from './Block';
import { NetworkParameters } from '../params/NetworkParameters';
import { ProtocolException } from '../exception/Exceptions';
import { VarInt } from './VarInt';
import { MessageSerializer } from './MessageSerializer';
import { Buffer } from 'buffer';

/**
 * <p>A protocol message that contains a repeated series of block headers, sent in response to the "getheaders" command.
 * This is useful when you want to traverse the chain but know you don't care about the block contents, for example,
 * because you have a freshly created wallet with no keys.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
export class HeadersMessage extends Message {
    public static readonly MAX_HEADERS = 2000;

    private blockHeaders: Block[];

    constructor(params: NetworkParameters, payload: Buffer);
    constructor(params: NetworkParameters, headers: Block[]);
    constructor(params: NetworkParameters, arg: Buffer | Block[]) {
        super(params);
        if (Array.isArray(arg)) {
            this.blockHeaders = arg;
        } else {
            this.blockHeaders = [];
            this.payload = arg;
            this.parse();
        }
    }

    protected bitcoinSerializeToStream(stream: any): void {
        VarInt.write(this.blockHeaders.length, stream);
        for (const header of this.blockHeaders) {
            stream.write(header.bitcoinSerialize()); // Serialize header and write to stream
            stream.write(Buffer.from([0])); // Trailing null byte
        }
    }

    protected parse(): void {
        let cursor = 0;
        const numHeaders = VarInt.read(this.payload, cursor).value;
        cursor += VarInt.read(this.payload, cursor).size;

        if (numHeaders > HeadersMessage.MAX_HEADERS) {
            throw new ProtocolException(`Too many headers: got ${numHeaders} which is larger than ${HeadersMessage.MAX_HEADERS}`);
        }

        this.blockHeaders = [];
        const serializer = this.params.getDefaultSerializer();

        for (let i = 0; i < numHeaders; ++i) {
            const newBlockHeader = serializer.makeBlock(this.payload, cursor, Message.UNKNOWN_LENGTH);
    
            cursor += newBlockHeader.getMessageSize(); // Assuming getMessageSize returns optimalEncodingMessageSize
            this.blockHeaders.push(newBlockHeader);
        }

        if (this.length === Message.UNKNOWN_LENGTH) {
            this.length = cursor - this.offset;
        }
    }

    public getBlockHeaders(): Block[] {
        return this.blockHeaders;
    }
}
