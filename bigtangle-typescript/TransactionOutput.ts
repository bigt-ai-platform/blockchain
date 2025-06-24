import { NetworkParameters } from './NetworkParameters';
import { Transaction } from './Transaction';
import { Coin } from './Coin';
import { Script } from './Script';
import { Message } from './Message';
import { Utils } from './Utils';
import { Address } from './Address';
import { ECKey } from './ECKey';
import { VarInt } from './VarInt';
import { ScriptBuilder } from './ScriptBuilder';
import { BitcoinSerializer } from './BitcoinSerializer';
import { Buffer } from 'buffer';

export class TransactionOutput extends Message {
    private value: Coin;
    private scriptPubKey: Script;
    private parent: Transaction;

    // Overload 1: TransactionOutput(NetworkParameters params, Transaction parent, Coin value, Script scriptPubKey)
    constructor(params: NetworkParameters, parent: Transaction, value: Coin, scriptPubKey: Script);
    // Overload 2: TransactionOutput(NetworkParameters params, Transaction parent, Coin value, ECKey to)
    constructor(params: NetworkParameters, parent: Transaction, value: Coin, to: ECKey);
    // Overload 3: TransactionOutput(NetworkParameters params, Transaction parent, byte[] payload, int offset)
    constructor(params: NetworkParameters, parent: Transaction, payloadBytes: Buffer, offset: number);
    // Unified constructor implementation
    constructor(
        params: NetworkParameters,
        parent: Transaction,
        valueOrPayloadBytes: Coin | Buffer,
        scriptPubKeyOrToOrOffset: Script | ECKey | number,
        offset?: number
    ) {
        // Determine if payloadBytesOrOutpoint is a Buffer (for parsing from raw bytes)
        const isPayloadBytes = valueOrPayloadBytes instanceof Buffer;

        // Call super constructor with payloadBytes if applicable
        super(params, isPayloadBytes ? valueOrPayloadBytes as Buffer : undefined, isPayloadBytes ? (scriptPubKeyOrToOrOffset as number) : undefined);

        this.parent = parent;

        if (valueOrPayloadBytes instanceof Coin) {
            this.value = valueOrPayloadBytes;
            if (scriptPubKeyOrToOrOffset instanceof Script) {
                // Overload 1: (params, parent, value, scriptPubKey)
                this.scriptPubKey = scriptPubKeyOrToOrOffset;
            } else if (scriptPubKeyOrToOrOffset instanceof ECKey) {
                // Overload 2: (params, parent, value, to)
                this.scriptPubKey = ScriptBuilder.createOutputScript(scriptPubKeyOrToOrOffset);
            } else {
                throw new Error("Invalid scriptPubKey or ECKey argument for TransactionOutput constructor.");
            }
            this.length = 8 + VarInt.sizeOf(this.scriptPubKey.getProgram().length) + this.scriptPubKey.getProgram().length;
        } else if (isPayloadBytes) {
            // Overload 3: (params, parent, payloadBytes, offset)
            this.value = new Coin(0, Buffer.alloc(0)); // Will be parsed
            this.scriptPubKey = new Script(Buffer.alloc(0)); // Will be parsed
            this.parse();
        } else {
            throw new Error("Invalid constructor arguments for TransactionOutput");
        }
    }

    protected parse(): void {
        this.cursor = this.offset;
        this.value = this.readCoin();
        const scriptLen = this.readVarInt();
        this.scriptPubKey = new Script(this.readBytes(scriptLen));
        this.length = this.cursor - this.offset;
    }

    public bitcoinSerializeToStream(stream: BitcoinSerializer): void {
        // Serialize value (Coin)
        Utils.uint64ToByteStreamLE(this.value.getValue(), stream);

        // Serialize tokenid length and tokenid
        const tokenid = this.value.getTokenid();
        stream.writeVarInt(tokenid.length);
        stream.write(tokenid);

        // Serialize scriptPubKey
        const scriptBytes = this.scriptPubKey.getProgram();
        stream.writeVarInt(scriptBytes.length);
        stream.write(scriptBytes);
    }

    getValue(): Coin {
        return this.value;
    }

    getScriptPubKey(): Script {
        return this.scriptPubKey;
    }

    getMessageSize(): number {
        return this.length;
    }

    setParent(parent: Transaction): void {
        this.parent = parent;
    }

    getScriptBytes(): Uint8Array {
        return this.scriptPubKey.getProgram();
    }

    getAddressFromP2PKHScript(params: NetworkParameters): Address | null {
        try {
            return this.scriptPubKey.isSentToRawPubKey() ? ECKey.fromPublicOnly(this.scriptPubKey.getPubKey()).toAddress(params) : this.scriptPubKey.getToAddress(params);
        } catch (e) {
            return null;
        }
    }

    toString(): string {
        return `Output: ${this.value.toString()} ScriptPubKey: ${Utils.HEX.encode(this.scriptPubKey.getProgram())}`;
    }
}
