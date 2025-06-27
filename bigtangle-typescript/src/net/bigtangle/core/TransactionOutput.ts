import { ChildMessage } from './ChildMessage';
import { Coin } from './Coin';
import { Script } from '../script/Script';
import { Address } from './Address';
import { ECKey } from './ECKey';
import { NetworkParameters } from './NetworkParameters';
import { Transaction } from './Transaction';import { VarInt } from './VarInt';
import { Utils } from '../utils/Utils';
import { Buffer } from 'buffer';
import { TransactionInput } from './TransactionInput';
import { Sha256Hash } from './Sha256Hash';
import { ScriptBuilder } from '../script/ScriptBuilder';
import { TransactionOutPoint } from './TransactionOutPoint';

 
/**
 * <p>
 * A TransactionOutput message contains a scriptPubKey that controls who is able
 * to spend its value. It is a sub-part of the Transaction message.
 * </p>
 * 
 * <p>
 * Instances of this class are not safe for use by multiple threads.
 * </p>
 */
export class TransactionOutput extends ChildMessage {
    private value!: Coin;
    private scriptBytes!: Buffer;
    private scriptPubKey: Script | null = null;
    private availableForSpending: boolean = true;
    private spentBy: TransactionInput | null = null;
    private scriptLen: number = 0;
    private tokenLen: number = 0;
    private description: string | null = null;
    private readonly index: number = 0;
 

    /**
     * Creates an output that sends 'value' to the given address (public key hash).
     * The amount should be created with something like Coin.valueOf(int, int).
     * Typically you would use Transaction#addOutput(Coin, Address) instead of creating a TransactionOutput directly.
     */
    static fromAddress(params: NetworkParameters, parent: Transaction | null, value: Coin, to: Address): TransactionOutput {
        return new TransactionOutput(params, parent, value, Buffer.from(ScriptBuilder.createOutputScript(to).getProgram()), 0);
    }

    /**
     * Creates an output that sends 'value' to the given public key using a simple CHECKSIG script (no addresses).
     * The amount should be created with something like Coin.valueOf(int, int).
     * Typically you would use Transaction#addOutput(Coin, ECKey) instead of creating an output directly.
     */
    static fromECKey(params: NetworkParameters, parent: Transaction | null, value: Coin, to: ECKey): TransactionOutput {
        return new TransactionOutput(params, parent, value, Buffer.from(ScriptBuilder.createOutputScript(to).getProgram()), 0);
    }

    // TypeScript constructor
    constructor(params: NetworkParameters, parent: Transaction | null, value: Coin, scriptBytes: Buffer, index: number = 0) {
        super(params);
        if (value.signum() < 0 && !value.equals(Coin.NEGATIVE_SATOSHI)) {
            throw new Error("Negative values not allowed");
        }
        this.value = value;
        this.scriptBytes = scriptBytes;
        this.setParent(parent as any);
        this.availableForSpending = true;
        this.index = index;
        this.length = this.value.getTokenid().length + VarInt.sizeOf(this.value.getTokenid().length)
            + VarInt.sizeOf(scriptBytes.length) + scriptBytes.length
            + VarInt.sizeOf(TransactionOutput.bigintToBuffer(this.value.getValue(), 32).length) + TransactionOutput.bigintToBuffer(this.value.getValue(), 32).length;
    }


    private calculateLength(): number {
        // This calculation needs to be accurate based on the Bitcoin serialization format
        // For now, a simplified version
        return (this.value ? this.value.getTokenid().length + VarInt.sizeOf(this.value.getTokenid().length) : 0) +
               (this.value ? VarInt.sizeOf(TransactionOutput.bigintToBuffer(this.value.getValue(), 32).length) + TransactionOutput.bigintToBuffer(this.value.getValue(), 32).length : 0) +
               VarInt.sizeOf(this.scriptBytes.length) + this.scriptBytes.length;
    }

    public getScriptPubKey(): Script {
        this.scriptPubKey ??= new Script(this.scriptBytes);
        return this.scriptPubKey;
    }

    public getAddressFromP2PKHScript(networkParameters: NetworkParameters): Address | null {
        if (this.getScriptPubKey().isSentToAddress()) {
            return this.getScriptPubKey().getToAddress(networkParameters);
        }
        return null;
    }

    public getAddressFromP2SH(networkParameters: NetworkParameters): Address | null {
        if (this.getScriptPubKey().isPayToScriptHash()) {
            return this.getScriptPubKey().getToAddress(networkParameters);
        }
        return null;
    }

    protected parse(): void {
        const vlen = this.readVarInt();
        const v = this.readBytes(vlen);
        this.tokenLen = this.readVarInt();
        this.value = new Coin(BigInt('0x' + Utils.HEX.encode(v)), this.readBytes(this.tokenLen));
        
        this.scriptLen = this.readVarInt();
        this.scriptBytes = this.readBytes(this.scriptLen);
        this.length = this.cursor - this.offset;
    }

    protected bitcoinSerializeToStream(stream: any): void {
        if (!this.scriptBytes) throw new Error("scriptBytes is null");
        if (!this.value) throw new Error("value is null");

        const valuebytes = TransactionOutput.bigintToBuffer(this.value.getValue(), 32);
        stream.write(VarInt.write(valuebytes.length, stream));
        stream.write(valuebytes);

        stream.write(VarInt.write(this.value.getTokenid().length, stream));
        stream.write(this.value.getTokenid());
        stream.write(VarInt.write(this.scriptBytes.length, stream));
        stream.write(this.scriptBytes);
    }

    public getValue(): Coin {
        if (!this.value) throw new Error("Value is null");
        return this.value;
    }

    public setValue(value: Coin): void {
        this.value = value;
        this.unCache();
    }

    public getIndex(): number {
        // Assuming this.index exists or is derived from outpoint/index logic
        return this.index ?? 0; // Default to 0 if not set
    }

    public markAsSpent(input: TransactionInput): void {
        if (!this.availableForSpending) throw new Error("Transaction already marked as spent.");
        this.availableForSpending = false;
        this.spentBy = input;
    }

    public markAsUnspent(): void {
        this.availableForSpending = true;
        this.spentBy = null;
    }

    public isAvailableForSpending(): boolean {
        return this.availableForSpending;
    }

    public getScriptBytes(): Buffer {
        return this.scriptBytes;
    }

    
 
 
    public toString(): string {
        try {
            const script = this.getScriptPubKey();
            let buf = `TxOut of ${this.value.toString()}`;
            if (script.isSentToAddress() || script.isPayToScriptHash()) {
                buf += ` to ${script.getToAddress(this.params)}`;
            } else if (script.isSentToRawPubKey()) {
                buf += ` to pubkey ${Utils.HEX.encode(script.getPubKey())}`;
            } else if (script.isSentToMultiSig()) {
                buf += ` to multisig`;
            } else {
                buf += ` (unknown type)`;
            }
            buf += ` script:${script}`;
            return buf;
        } catch (e: any) {
            throw new Error(e);
        }
    }

    public getSpentBy(): TransactionInput | null {
        return this.spentBy;
    }

    public getParentTransaction(): Transaction | null {
        return this.parent as unknown as Transaction;
    }

    public getParentTransactionHash(): Sha256Hash | null {
        return this.parent === null ? null : this.parent.getHash();
    }

    public getOutPointFor(containingBlockHash: Sha256Hash): TransactionOutPoint {
        return new TransactionOutPoint(this.params, this.getIndex(), containingBlockHash, this.getParentTransaction());
    }

    public duplicateDetached(): TransactionOutput {
        return new TransactionOutput(this.params, null, this.value, Buffer.from(this.scriptBytes));
    }

    public equals(o: any): boolean {
        if (this === o) return true;
        if (o === null || !(o instanceof TransactionOutput)) return false;
        const other = o as TransactionOutput;
        return this.value.equals(other.value) &&
               (this.parent === null || (this.parent === other.parent && this.getIndex() === other.getIndex())) &&
               this.scriptBytes.equals(other.scriptBytes);
    }

    public hashCode(): number {
        let result = 17;
        result = 31 * result + this.value.hashCode();
        result = 31 * result + (this.parent && typeof (this.parent as any).hashCode === 'function' ? (this.parent as any).hashCode() : 0);
        for (let i = 0; i < this.scriptBytes.length; i++) {
            result = 31 * result + this.scriptBytes[i];
        }
        return result;
    }

    public getDescription(): string | null {
        return this.description;
    }

    public setDescription(description: string | null): void {
        this.description = description;
    }

    // Utility function to convert bigint to Buffer
    private static bigintToBuffer(value: bigint, length: number): Buffer {
        let hex = value.toString(16);
        if (hex.length % 2) hex = '0' + hex;
        const buf = Buffer.from(hex, 'hex');
        if (buf.length < length) {
            const pad = Buffer.alloc(length - buf.length, 0);
            return Buffer.concat([pad, buf]);
        }
        return buf;
    }

    // Add this static method to parse a TransactionOutput from a buffer
    static parseFromBuffer(buffer: Buffer, offset: number): [TransactionOutput, number] {
        // Placeholder implementation: replace with actual parsing logic
        // For now, just return a new TransactionOutput and assume size is 0
        // You should implement the actual parsing logic as per your protocol
        // Provide placeholder arguments for now; replace with actual parsed values as needed
        return [new TransactionOutput({} as NetworkParameters, null, Coin.ZERO, Buffer.alloc(0)), 0];
    }
}