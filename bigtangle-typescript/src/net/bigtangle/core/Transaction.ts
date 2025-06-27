import { ChildMessage } from './ChildMessage';
import { Sha256Hash } from './Sha256Hash';
import { TransactionInput } from './TransactionInput';
import { TransactionOutput } from './TransactionOutput';
import { NetworkParameters } from './NetworkParameters';
import { MessageSerializer } from './MessageSerializer';
import { Coin } from './Coin';
import { Buffer } from 'buffer';
import { VerificationException } from '../exception/VerificationException';


// Implement ByteArrayOutputStream outside the class
class ByteArrayOutputStream {
    private buffer: Buffer = Buffer.alloc(0);
    
    public write(data: Buffer | number): void {
        if (typeof data === 'number') {
            this.buffer = Buffer.concat([this.buffer, Buffer.from([data])]);
        } else {
            this.buffer = Buffer.concat([this.buffer, data]);
        }
    }
    
    public toByteArray(): Buffer {
        return this.buffer;
    }
}

// Define OutputStream type
type OutputStream = {
    write: (data: Buffer | number) => void;
};

export enum SigHash {
    ALL = 1,
    NONE = 2,
    SINGLE = 3,
    ANYONECANPAY = 0x80,
    ANYONECANPAY_ALL = 0x81,
    ANYONECANPAY_NONE = 0x82,
    ANYONECANPAY_SINGLE = 0x83,
    UNSET = 0
}

export enum Purpose {
    UNKNOWN,
    USER_PAYMENT,
    KEY_ROTATION,
    ASSURANCE_CONTRACT_CLAIM,
    ASSURANCE_CONTRACT_PLEDGE,
    ASSURANCE_CONTRACT_STUB,
    RAISE_FEE
}

export class Transaction extends ChildMessage {
 public static readonly UNCONNECTED = 0xFFFFFFFF;

    protected parse(): void {
        // Parse the transaction from the provided bytes
        if (!this.payload) {
            throw new Error('No payload to parse');
        }
        let offset = 0;
        // Version (4 bytes, little endian)
        this.version = this.payload.readInt32LE(offset);
        offset += 4;

        // Input count (varint)
        const [inputCount, inputCountSize] = this.readVarIntFromBuffer(this.payload, offset);
        offset += inputCountSize;
        this.inputs = [];
        for (let i = 0; i < inputCount; i++) {
            const [input, size] = TransactionInput.parseFromBuffer(this.payload, offset);
            this.inputs.push(input);
            offset += size;
        }

        // Output count (varint)
        const [outputCount, outputCountSize] = this.readVarIntFromBuffer(this.payload, offset);
        offset += outputCountSize;
        this.outputs = [];
        for (let i = 0; i < outputCount; i++) {
            const [output, size] = TransactionOutput.parseFromBuffer(this.payload, offset);
            this.outputs.push(output);
            offset += size;
        }

        // LockTime (4 bytes, little endian)
        this.lockTime = this.payload.readUInt32LE(offset);
        offset += 4;

        this.length = offset;
    }

    // Helper to read a Bitcoin-style varint
    private readVarIntFromBuffer(buffer: Buffer, offset: number): [number, number] {
        const first = buffer[offset];
        if (first < 0xfd) {
            return [first, 1];
        } else if (first === 0xfd) {
            return [buffer.readUInt16LE(offset + 1), 3];
        } else if (first === 0xfe) {
            return [buffer.readUInt32LE(offset + 1), 5];
        } else {
            // 0xff
            return [Number(buffer.readBigUInt64LE(offset + 1)), 9];
        }
    }
    public static readonly LOCKTIME_THRESHOLD = 500000000;
    public static readonly LOCKTIME_THRESHOLD_BIG = BigInt(500000000);
    // Placeholder values until NetworkParameters is implemented
    public static readonly REFERENCE_DEFAULT_MIN_TX_FEE = Coin.ZERO;
    public static readonly MIN_NONDUST_OUTPUT = Coin.ZERO;

    private version: number = 1;
    private inputs: TransactionInput[] = [];
    private outputs: TransactionOutput[] = [];
    private lockTime: number = 0;
    private hash: Sha256Hash | null = null;
    private appearsInHashes: Map<Sha256Hash, number> | null = null;
    private optimalEncodingMessageSize: number = 0;
    private purpose: Purpose = Purpose.UNKNOWN;
    private memo: string | null = null;
    private data: Buffer | null = null;
    private dataSignature: Buffer | null = null;
    private dataClassName: string | null = null;
    private toAddressInSubtangle: Buffer | null = null;

    public bitcoinSerialize(): Uint8Array {
        try {
            const output = new ByteArrayOutputStream();
            // Placeholder: call the serialization method
            this.bitcoinSerializeToStream(output);
            return output.toByteArray();
        } catch (e) {
            return new Uint8Array(0);
        }
    }
    
   
    
    public getMessageSize(): number {
        return this.length;
    }
    
    public unCache(): void {
        this.hash = null;
    }

    public adjustLength(adjustment: number): void;
    public adjustLength(newArraySize: number, adjustment: number): void;
    public adjustLength(arg1: number, arg2?: number): void {
        // Placeholder implementation
        // Length adjustment logic would go here
        // If called with one argument, treat as (adjustment)
        // If called with two arguments, treat as (newArraySize, adjustment)
    }

    public length: number = 0;  // Add length property
    
    constructor(params?: NetworkParameters, bytes?: Buffer, offset: number = 0, serializer?: MessageSerializer) {
        if (!params) {
            throw new Error("NetworkParameters must be provided");
        }
        super(params, bytes, offset, serializer);
        if (!bytes) {
            this.inputs = [];
            this.outputs = [];
            this.length = 8;
        }
    }

    public getHash(): Sha256Hash {
        // Use static method to create a dummy hash
        return Sha256Hash.wrap(Buffer.alloc(32));
    }

    setHash(hash: Sha256Hash): void {
        this.hash = hash;
    }

    // Simplified placeholder implementations
    isCoinBase(): boolean {
        return false;
    }

    // Placeholder for missing bitcoinSerializeToStream method
    bitcoinSerializeToStream(stream: OutputStream): void {
        // Implementation would go here
    }

    getLockTime(): number {
        return this.lockTime;
    }

    setLockTime(lockTime: number): void {
        this.unCache();
        this.lockTime = lockTime;
    }

    getVersion(): number {
        return this.version;
    }

    setVersion(version: number): void {
        this.version = version;
        this.unCache();
    }

    getInputs(): TransactionInput[] {
        return [...this.inputs];
    }

    getOutputs(): TransactionOutput[] {
        return [...this.outputs];
    }

    getInput(index: number): TransactionInput {
        return this.inputs[index];
    }

    getOutput(index: number): TransactionOutput {
        return this.outputs[index];
    }

    // Simplified placeholder implementations

    getPurpose(): Purpose {
        return this.purpose;
    }

    setPurpose(purpose: Purpose): void {
        this.purpose = purpose;
        this.unCache();
    }

    getMemo(): string | null {
        return this.memo;
    }

    setMemo(memo: string | null): void {
        this.memo = memo;
        this.unCache();
    }

    getData(): Buffer | null {
        return this.data;
    }

    setData(data: Buffer | null): void {
        this.data = data;
        this.unCache();
    }

    getDataSignature(): Buffer | null {
        return this.dataSignature;
    }

    setDataSignature(dataSignature: Buffer | null): void {
        this.dataSignature = dataSignature;
        this.unCache();
    }

    getDataClassName(): string | null {
        return this.dataClassName;
    }

    setDataClassName(dataClassName: string | null): void {
        this.dataClassName = dataClassName;
        this.unCache();
    }

    public toString(): string {
        return `Transaction: ${this.getHash().toString()}`;
    }

    getToAddressInSubtangle(): Buffer | null {
        return this.toAddressInSubtangle;
    }

    setToAddressInSubtangle(toAddressInSubtangle: Buffer | null): void {
        this.toAddressInSubtangle = toAddressInSubtangle;
        this.unCache();
    }

    public addInput(input: TransactionInput): void {
        input.setParent(this as any);
        this.inputs.push(input);
        this.length += input.getMessageSize ? input.getMessageSize() : 0;
        this.unCache();
    }

    public addOutput(output: TransactionOutput): void {
        output.setParent(this as any);
        this.outputs.push(output);
        this.length += output.getMessageSize ? output.getMessageSize() : 0;
        this.unCache();
    }

    /**
     * Adds a coinbase input and output to this transaction.
     * @param pubKeyTo The public key buffer to pay to.
     * @param value The coinbase value.
     * @param tokenInfo Optional token info for token blocks.
     * @param memoInfo Optional memo info.
     */
    public addCoinbaseTransaction(pubKeyTo: Buffer, value: Coin, tokenInfo?: any, memoInfo?: any): void {
        // Set memo if provided
        if (memoInfo) {
            this.setMemo(memoInfo.toString());
        }
        // Set token data if provided
        if (tokenInfo) {
            this.setDataClassName('TOKEN');
            if (typeof tokenInfo.toByteArray === 'function') {
                this.setData(Buffer.from(tokenInfo.toByteArray()));
            }
        }
        // Build coinbase input
        const inputBuilder = new (require('./ScriptBuilder').ScriptBuilder)();
        inputBuilder.data(Buffer.from([0, 0])); // Placeholder coinbase script
        this.addInput(new (require('./TransactionInput').TransactionInput)(this.params, this, Buffer.from(inputBuilder.build().getProgram())));
        // Build coinbase output
        const scriptPubKey = require('./ScriptBuilder').ScriptBuilder.createOutputScript(require('./ECKey').ECKey.fromPublic(pubKeyTo));
        this.addOutput(new (require('./TransactionOutput').TransactionOutput)(this.params, this, value, Buffer.from(scriptPubKey.getProgram())));
    }

    public getSigOpCount(): number {
        // TODO: Implement actual sigops counting logic based on scripts
        return 0;
    }

    public verify(): void {
        // Memo size check (uncomment if you want to enforce)
        // if (this.getMemo() && this.getMemo()!.length > NetworkParameters.MAX_TRANSACTION_MEMO_SIZE) {
        //     throw new VerificationException(`memo size too large MAX ${NetworkParameters.MAX_TRANSACTION_MEMO_SIZE}`);
        // }
        // Data class name and signature size checks (uncomment if you want to enforce)
        // if (this.getDataClassName() && this.getDataClassName()!.length > NetworkParameters.MAX_TRANSACTION_MEMO_SIZE) {
        //     throw new VerificationException(`getDataClassName size too large MAX ${NetworkParameters.MAX_TRANSACTION_MEMO_SIZE}`);
        // }
        // if (this.getDataSignature() && this.getDataSignature()!.length > NetworkParameters.MAX_TRANSACTION_MEMO_SIZE) {
        //     throw new VerificationException(`getDataSignature size too large MAX ${NetworkParameters.MAX_TRANSACTION_MEMO_SIZE}`);
        // }

        // Check for duplicate outpoints
        const outpoints = new Set<string>();
        for (const input of this.inputs) {
            const outpointStr = input.getOutpoint().toString();
            if (outpoints.has(outpointStr)) {
                throw new VerificationException.DuplicatedOutPoint();
            }
            outpoints.add(outpointStr);
        }

        // Check for negative output values
        try {
            for (const output of this.outputs) {
                if (output.getValue().signum() < 0) {
                    throw new VerificationException.NegativeValueOutput();
                }
            }
        } catch (e: any) {
            throw new VerificationException.ExcessiveValue();
        }

        // Coinbase checks
        if (this.isCoinBase()) {
            const firstInput = this.inputs[0];
            const scriptLen = firstInput.getScriptBytes().length;
            if (scriptLen < 2 || scriptLen > 100) {
                throw new VerificationException.CoinbaseScriptSizeOutOfRange();
            }
        } else {
            for (const input of this.inputs) {
                if (input.isCoinBase()) {
                    throw new VerificationException.UnexpectedCoinbaseInput();
                }
            }
        }
    }

    // Additional methods would be implemented here...
}
