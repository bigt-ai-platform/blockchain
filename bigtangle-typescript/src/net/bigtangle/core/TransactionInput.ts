import { ChildMessage } from './ChildMessage';
import { TransactionOutPoint } from './TransactionOutPoint';
import { Script } from '../script/Script';
import { VarInt } from './VarInt';
import { Coin } from './Coin';
import { NetworkParameters } from './NetworkParameters';
import { Transaction } from './Transaction';
 
import { VerificationException } from '../exception/VerificationException';
import { Utils } from '../utils/Utils';
import { Buffer } from 'buffer';
import { TransactionOutput } from './TransactionOutput';
import { Address } from './Address';

/**
 * <p>A transfer of coins from one address to another creates a transaction in which the outputs
 * can be claimed by the recipient in the input of another transaction. You can imagine a
 * transaction as being a module which is wired up to others, the inputs of one have to be wired
 * to the outputs of another. The exceptions are coinbase transactions, which create new coins.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
export class TransactionInput extends ChildMessage {
    /** Magic sequence number that indicates there is no sequence number. */
    public static readonly NO_SEQUENCE = 0xFFFFFFFF;
    private static readonly EMPTY_ARRAY = Buffer.alloc(0);
    // Magic outpoint index that indicates the input is in fact unconnected.
    private static readonly UNCONNECTED = 0xFFFFFFFF;

    private sequence!: number;
    private outpoint!: TransactionOutPoint;
    private scriptBytes!: Buffer;
    private scriptSig: Script | null = null;
    private readonly value: Coin | null = null;

    constructor(params: NetworkParameters, parentTransaction: Transaction | null, scriptBytes: Buffer);
    constructor(params: NetworkParameters, parentTransaction: Transaction | null, scriptBytes: Buffer, outpoint: TransactionOutPoint);
    constructor(params: NetworkParameters, parentTransaction: Transaction | null, scriptBytes: Buffer, outpoint: TransactionOutPoint, value: Coin | null);
    constructor(params: NetworkParameters, parentTransaction: Transaction | null, payload: Buffer, offset: number);
    constructor(params: NetworkParameters, parentTransaction: Transaction | null, payload: Buffer, offset: number, serializer: any);
    constructor(...args: any[]) {
        // Overload 1: (params, parentTransaction, scriptBytes)
        // Overload 2: (params, parentTransaction, scriptBytes, outpoint)
        // Overload 3: (params, parentTransaction, scriptBytes, outpoint, value)
        // Overload 4: (params, parentTransaction, payload, offset)
        // Overload 5: (params, parentTransaction, payload, offset, serializer)
        const params = args[0];
        const parentTransaction = args[1];
        super(params);
        this.setParent(parentTransaction);

        if (Buffer.isBuffer(args[2]) && typeof args[3] === 'undefined') {
            // (params, parentTransaction, scriptBytes)
            this.scriptBytes = args[2];
            this.outpoint = new TransactionOutPoint(params, Buffer.alloc(36), 0); // dummy outpoint
            this.sequence = TransactionInput.NO_SEQUENCE;
            (this as any).value = null;
            this.length = 40 + (this.scriptBytes === null ? 1 : VarInt.sizeOf(this.scriptBytes.length) + this.scriptBytes.length);
        } else if (Buffer.isBuffer(args[2]) && args[3] instanceof TransactionOutPoint && typeof args[4] === 'undefined') {
            // (params, parentTransaction, scriptBytes, outpoint)
            this.scriptBytes = args[2];
            this.outpoint = args[3];
            this.sequence = TransactionInput.NO_SEQUENCE;
            (this as any).value = null;
            this.length = 40 + (this.scriptBytes === null ? 1 : VarInt.sizeOf(this.scriptBytes.length) + this.scriptBytes.length);
        } else if (Buffer.isBuffer(args[2]) && args[3] instanceof TransactionOutPoint && (args[4] === null || args[4] instanceof Coin)) {
            // (params, parentTransaction, scriptBytes, outpoint, value)
            this.scriptBytes = args[2];
            this.outpoint = args[3];
            this.sequence = TransactionInput.NO_SEQUENCE;
            (this as any).value = args[4];
            this.length = 40 + (this.scriptBytes === null ? 1 : VarInt.sizeOf(this.scriptBytes.length) + this.scriptBytes.length);
        } else if (Buffer.isBuffer(args[2]) && typeof args[3] === 'number' && typeof args[4] === 'undefined') {
            // (params, parentTransaction, payload, offset)
            this.payload = args[2];
            this.offset = args[3];
            this.cursor = this.offset;
            this.parse();
        } else if (Buffer.isBuffer(args[2]) && typeof args[3] === 'number' && typeof args[4] !== 'undefined') {
            // (params, parentTransaction, payload, offset, serializer)
            this.payload = args[2];
            this.offset = args[3];
            this.cursor = this.offset;
            this.serializer = args[4];
            this.parse();
        } else {
            throw new Error('Invalid constructor arguments for TransactionInput');
        }
    }

    protected parse(): void {
        this.outpoint = new TransactionOutPoint(this.params, this.payload, this.cursor, this, this.serializer);
        this.cursor += this.outpoint.getMessageSize();
        const scriptLen = this.readVarInt();
        this.length = this.cursor - this.offset + scriptLen + 4;
        this.scriptBytes = this.readBytes(scriptLen);
        this.sequence = this.readUint32();
        // Check for connected output (optional field in Java, not directly in Bitcoin protocol)
        // This part needs careful consideration as it's not standard Bitcoin serialization
        // For now, assuming it's not present or handled differently
        // if (this.readUint32() === 1) {
        //     this.outpoint.connectedOutput = new TransactionOutput(this.params, this.parent as Transaction, this.payload, this.cursor);
        //     this.cursor += this.outpoint.connectedOutput.getMessageSize();
        // }
    }

    protected bitcoinSerializeToStream(stream: any): void {
        stream.write(this.outpoint.bitcoinSerialize());
        VarInt.write(this.scriptBytes.length, stream);
        stream.write(this.scriptBytes);
        Utils.uint32ToByteStreamLE(this.sequence, stream);
        // This part is not standard Bitcoin serialization, so commenting out for now
        // Utils.uint32ToByteStreamLE(this.outpoint.connectedOutput !== null ? 1 : 0, stream);
        // if (this.outpoint.connectedOutput !== null) {
        //     this.outpoint.connectedOutput.bitcoinSerializeToStream(stream);
        // }
    }

    /**
     * Coinbase transactions have special inputs with hashes of zero. If this is such an input, returns true.
     */
    public isCoinBase(): boolean {
        return this.outpoint.isCoinBase();
    }

    /**
     * Returns the script that is fed to the referenced output (scriptPubKey) script in order to satisfy it: usually
     * contains signatures and maybe keys, but can contain arbitrary data if the output script accepts it.
     */
    public getScriptSig(): Script {
        // Transactions that generate new coins don't actually have a script. Instead this
        // parameter is overloaded to be something totally different.
        this.scriptSig ??= new Script(this.scriptBytes);
        return this.scriptSig;
    }

    /** Set the given program as the scriptSig that is supposed to satisfy the connected output script. */
    public setScriptSig(scriptSig: Script): void {
        this.scriptSig = scriptSig;
        // TODO: This should all be cleaned up so we have a consistent internal representation.
        this.setScriptBytes(Buffer.from(scriptSig.getProgram()));
    }

    /**
     * Convenience method that returns the from address of this input by parsing the scriptSig. The concept of a
     * "from address" is not well defined in Bitcoin and you should not assume that senders of a transaction can
     * actually receive coins on the same address they used to sign (e.g. this is not true for shared wallets).
     */
    public getFromAddress(): Address {
        if (this.isCoinBase()) {
            throw new Error("This is a coinbase transaction which generates new coins. It does not have a from address.");
        }
        return this.getScriptSig().getFromAddress(this.params);
    }

    /**
     * Sequence numbers allow participants in a multi-party transaction signing protocol to create new versions of the
     * transaction independently of each other. Newer versions of a transaction can replace an existing version that's
     * in nodes memory pools if the existing version is time locked. See the Contracts page on the Bitcoin wiki for
     * examples of how you can use this feature to build contract protocols.
     */
    public getSequenceNumber(): number {
        return this.sequence;
    }

    /**
     * Sequence numbers allow participants in a multi-party transaction signing protocol to create new versions of the
     * transaction independently of each other. Newer versions of a transaction can replace an existing version that's
     * in nodes memory pools if the existing version is time locked. See the Contracts page on the Bitcoin wiki for
     * examples of how you can use this feature to build contract protocols.
     */
    public setSequenceNumber(sequence: number): void {
        this.unCache();
        this.sequence = sequence;
    }

    /**
     * @return The previous output transaction reference, as an OutPoint structure.  This contains the 
     * data needed to connect to the output of the transaction we're gathering coins from.
     */
    public getOutpoint(): TransactionOutPoint {
        return this.outpoint;
    }

    /**
     * The "script bytes" might not actually be a script. In coinbase transactions where new coins are minted there
     * is no input transaction, so instead the scriptBytes contains some extra stuff (like a rollover nonce) that we
     * don't care about much. The bytes are turned into a Script object (cached below) on demand via a getter.
     * @return the scriptBytes
     */
    public getScriptBytes(): Buffer {
        return this.scriptBytes;
    }

    /** Clear input scripts, e.g. in preparation for signing. */
    public clearScriptBytes(): void {
        this.setScriptBytes(TransactionInput.EMPTY_ARRAY);
    }

    /**
     * @param scriptBytes the scriptBytes to set
     */
    public setScriptBytes(scriptBytes: Buffer): void {
        this.unCache();
        this.scriptSig = null;
        const oldLength = this.length;
        this.scriptBytes = scriptBytes;
        // 40 = previous_outpoint (36) + sequence (4)
        const newLength = 40 + (scriptBytes === null ? 1 : VarInt.sizeOf(scriptBytes.length) + scriptBytes.length);
        this.adjustLength(newLength - oldLength);
    }

    /**
     * @return The Transaction that owns this input.
     */
    public getParentTransaction(): Transaction {
        if (this.parent instanceof Transaction) {
            return this.parent;
        }
        throw new Error("Parent is not a Transaction or is null.");
    }

    /**
     * @return Value of the output connected to this input, if known. Null if unknown.
     */
    public getValue(): Coin | null {
        return this.value;
    }

    public hasSequence(): boolean {
        return this.sequence !== TransactionInput.NO_SEQUENCE;
    }

    /**
     * Returns whether this input will cause a transaction to opt into the
     * <a href="https://github.com/bitcoin/bips/blob/master/bip-0125.mediawiki">full replace-by-fee </a> semantics.
     */
    public isOptInFullRBF(): boolean {
        return this.sequence < TransactionInput.NO_SEQUENCE - 1;
    }

    /**
     * For a connected transaction, runs the script against the connected pubkey and verifies they are correct.
     * @throws ScriptException if the script did not verify.
     * @throws VerificationException If the outpoint doesn't match the given output.
     */
    public verify(): void {
        const fromTx = this.getOutpoint().fromTx;
        const spendingIndex = this.getOutpoint().getIndex();
        if (!fromTx) throw new Error("Not connected");
        const output = fromTx.getOutput(spendingIndex);
        this.verifyWithOutput(output);
    }

    /**
     * Verifies that this input can spend the given output. Note that this input must be a part of a transaction.
     * Also note that the consistency of the outpoint will be checked, even if this input has not been connected.
     *
     * @param output the output that this input is supposed to spend.
     * @throws ScriptException If the script doesn't verify.
     * @throws VerificationException If the outpoint doesn't match the given output.
     */
    public verifyWithOutput(output: TransactionOutput): void {
        if (output.parent !== null) {
            // This part needs to be re-evaluated based on how parentTransaction and parentBlock are handled in TS
            // if (!this.getOutpoint().getHash().equals(Sha256Hash.of( Utils.addAll(
            //         output.getParentTransaction().getParentBlock().getHash().getBytes(), output.getParentTransaction().getHash().getBytes()))))
            //     throw new VerificationException("This input does not refer to the tx containing the output.");
            if (this.getOutpoint().getIndex() !== output.getIndex())
                throw new VerificationException("This input refers to a different output on the given tx.");
        }
        const pubKey = output.getScriptPubKey();
        const myIndex = this.getParentTransaction().getInputs().indexOf(this);
        const r = this.getScriptSig();
        r.correctlySpends(this.getParentTransaction(), myIndex, pubKey, Script.ALL_VERIFY_FLAGS);
    }

    /**
     * Returns the connected output, assuming the input was connected with
     * {@link TransactionInput#connect(TransactionOutput)} or variants at some point. If it wasn't connected, then
     * this method returns null.
     */
    public getConnectedOutput(): TransactionOutput | null {
        return this.getOutpoint().getConnectedOutput();
    }

    /**
     * Returns the connected transaction, assuming the input was connected with
     * {@link TransactionInput#connect(TransactionOutput)} or variants at some point. If it wasn't connected, then
     * this method returns null.
     */
    public getConnectedTransaction(): Transaction | null {
        return this.getOutpoint().fromTx;
    }

    /** Returns a copy of the input detached from its containing transaction, if need be. */
    public duplicateDetached(): TransactionInput {
        return new TransactionInput(this.params, null, Buffer.from(this.bitcoinSerialize()), 0);
    }

    public equals(o: any): boolean {
        if (this === o) return true;
        if (o === null || !(o instanceof TransactionInput)) return false;
        const other = o;
        return this.sequence === other.sequence &&
               (this.parent === other.parent || (this.parent !== null && other.parent !== null && typeof (this.parent as any).equals === "function" && (this.parent as any).equals(other.parent))) &&
               this.outpoint.equals(other.outpoint) &&
               this.scriptBytes.equals(other.scriptBytes);
    }

    public hashCode(): number {
        let result = 17;
        result = 31 * result + this.sequence;
        result = 31 * result + this.outpoint.hashCode();
        for (const byte of this.scriptBytes) {
            result = 31 * result + byte;
        }
        return result;
    }

    /**
     * Returns a human readable debug string.
     */
    public toString(): string {
        let s = "TxIn";
        try {
            if (this.isCoinBase()) {
                s += ": COINBASE";
            } else {
                s += ` for [${this.outpoint}]: ${this.getScriptSig()}`;
                const flags: string[] = [];
                if (this.hasSequence()) {
                    flags.push(`sequence: ${this.sequence.toString(16)}`);
                }
                if (this.isOptInFullRBF()) {
                    flags.push("opts into full RBF");
                }
                if (flags.length > 0) {
                    s += ` (${flags.join(", ")})`;
                }
            }
            return s;
        } catch (e: any) {
            throw new Error(e);
        }
    }


    static parseFromBuffer(buffer: Buffer, offset: number): [TransactionInput, number] {
        // Placeholder implementation: parse fields as needed
        // For now, just return a new TransactionInput and a dummy size (e.g., 40)
        // Replace this with actual parsing logic
        // You need to provide actual values for params, parentTransaction, and scriptBytes here
        // For placeholder purposes, using 'null' for parentTransaction and an empty Buffer for scriptBytes
        // Replace 'params' with a valid NetworkParameters instance as needed
        const params: NetworkParameters = {} as NetworkParameters; // TODO: Replace with actual instance
        const parentTransaction: Transaction | null = null;
        const scriptBytes: Buffer = Buffer.alloc(0);
        const input = new TransactionInput(params, parentTransaction, scriptBytes);
        const size = 40; // Replace with actual parsed size
        return [input, size];
    }
}