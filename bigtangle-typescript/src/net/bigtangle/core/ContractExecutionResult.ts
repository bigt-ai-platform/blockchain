import { Sha256Hash } from './Sha256Hash';
import { Transaction } from './Transaction';
import { ContractEventRecord } from './ContractEventRecord';
import { Spent } from './Spent';
import { Utils } from '../utils/Utils';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class ContractExecutionResult extends Spent {
    public contracttokenid: string | null = null;
    public prevblockhash: Sha256Hash | null = null;
    public chainlength: number = 0;
    public referencedBlocks: Set<Sha256Hash> = new Set();
    public outputTxHash: Sha256Hash | null = null;
    public cancelRecords: Set<Sha256Hash> = new Set();
    public remainderRecords: Set<Sha256Hash> = new Set();

    // Not part of toByteArray, not persistent
    public outputTx: Transaction | null = null;
    public remainderContractEventRecord: Set<ContractEventRecord> | null = null;

    constructor(
        contractid?: string,
        outputTxHash?: Sha256Hash,
        outputTx?: Transaction,
        prevblockhash?: Sha256Hash,
        cancelRecords?: Set<Sha256Hash>,
        remainderRecords?: Set<Sha256Hash>,
        inserttime?: number,
        remainderContractEventRecord?: Set<ContractEventRecord>,
        referencedOrderBlocks?: Set<Sha256Hash>,
        chainlength?: number
    ) {
        super();
        if (contractid) this.contracttokenid = contractid;
        if (outputTxHash) this.outputTxHash = outputTxHash;
        if (outputTx) this.outputTx = outputTx;
        if (prevblockhash) this.prevblockhash = prevblockhash;
        if (cancelRecords) this.cancelRecords = cancelRecords;
        if (remainderRecords) this.remainderRecords = remainderRecords;
        if (inserttime) this.setTime(inserttime);
        if (remainderContractEventRecord) this.remainderContractEventRecord = remainderContractEventRecord;
        if (referencedOrderBlocks) this.referencedBlocks = referencedOrderBlocks;
        if (chainlength) this.chainlength = chainlength;
    }

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream();
        try {
            dos.write(Buffer.from(super.toByteArray()));
            dos.writeNBytesString(this.contracttokenid || "");
            dos.writeBytes(this.outputTxHash ? this.outputTxHash.bytes : Sha256Hash.ZERO_HASH.bytes);
            dos.writeBytes(this.prevblockhash ? this.prevblockhash.bytes : Sha256Hash.ZERO_HASH.bytes);
            dos.writeLong(this.chainlength);

            dos.writeInt(this.cancelRecords.size);
            for (const c of this.cancelRecords) {
                dos.writeBytes(c.bytes);
            }
            dos.writeInt(this.remainderRecords.size);
            for (const c of this.remainderRecords) {
                dos.writeBytes(c.bytes);
            }
            dos.writeInt(this.referencedBlocks.size);
            for (const c of this.referencedBlocks) {
                dos.writeBytes(c.bytes);
            }

            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }

    public parseDIS(dis: DataInputStream): ContractExecutionResult {
        super.parseDIS(dis);
        this.contracttokenid = dis.readNBytesString();
        this.outputTxHash = Sha256Hash.wrap(dis.readBytes(32));
        this.prevblockhash = Sha256Hash.wrap(dis.readBytes(32));
        this.chainlength = dis.readLong();

        this.cancelRecords = new Set();
        let cancelRecordsSize = dis.readInt();
        for (let i = 0; i < cancelRecordsSize; i++) {
            this.cancelRecords.add(Sha256Hash.wrap(dis.readBytes(32)));
        }
        this.remainderRecords = new Set();
        let remainderRecordsSize = dis.readInt();
        for (let i = 0; i < remainderRecordsSize; i++) {
            this.remainderRecords.add(Sha256Hash.wrap(dis.readBytes(32)));
        }
        let blocksSize = dis.readInt();
        this.referencedBlocks = new Set();
        for (let i = 0; i < blocksSize; i++) {
            this.referencedBlocks.add(Sha256Hash.wrap(dis.readBytes(32)));
        }

        return this;
    }

    public parseChecked(buf: Uint8Array): ContractExecutionResult {
        try {
            return this.parse(buf);
        } catch (e: any) {
            throw new Error(e);
        }
    }

    public parse(buf: Uint8Array): ContractExecutionResult {
        const bain = new DataInputStream(Buffer.from(buf));
        try {
            this.parseDIS(bain);
            bain.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return this;
    }

    public getOutputTxHash(): Sha256Hash | null {
        return this.outputTxHash;
    }

    public getOutputTx(): Transaction | null {
        return this.outputTx;
    }

    public setOutputTx(outputTx: Transaction | null): void {
        this.outputTx = outputTx;
    }

    public getContracttokenid(): string | null {
        return this.contracttokenid;
    }

    public setContracttokenid(contracttokenid: string | null): void {
        this.contracttokenid = contracttokenid;
    }

    public getPrevblockhash(): Sha256Hash | null {
        return this.prevblockhash;
    }

    public setPrevblockhash(prevblockhash: Sha256Hash | null): void {
        this.prevblockhash = prevblockhash;
    }

    public getCancelRecords(): Set<Sha256Hash> {
        return this.cancelRecords;
    }

    public setCancelRecords(cancelRecords: Set<Sha256Hash>): void {
        this.cancelRecords = cancelRecords;
    }

    public getRemainderRecords(): Set<Sha256Hash> {
        return this.remainderRecords;
    }

    public setRemainderRecords(remainderRecords: Set<Sha256Hash>): void {
        this.remainderRecords = remainderRecords;
    }

    public getRemainderContractEventRecord(): Set<ContractEventRecord> | null {
        return this.remainderContractEventRecord;
    }

    public setRemainderContractEventRecord(remainderContractEventRecord: Set<ContractEventRecord> | null): void {
        this.remainderContractEventRecord = remainderContractEventRecord;
    }

    public getReferencedBlocks(): Set<Sha256Hash> {
        return this.referencedBlocks;
    }

    public setReferencedBlocks(referencedBlocks: Set<Sha256Hash>): void {
        this.referencedBlocks = referencedBlocks;
    }

    public getChainlength(): number {
        return this.chainlength;
    }

    public setChainlength(chainlength: number): void {
        this.chainlength = chainlength;
    }

    public toString(): string {
        return ` [contracttokenid=${this.contracttokenid}, prevblockhash=${this.prevblockhash}` +
               `, referencedBlocks=${Array.from(this.referencedBlocks).map(h => h.toString())}` +
               `, outputTxHash=${this.outputTxHash}, cancelRecords=${Array.from(this.cancelRecords).map(h => h.toString())}` +
               `, remainderRecords=${Array.from(this.remainderRecords).map(h => h.toString())}, outputTx=${this.outputTx}` +
               `, remainderContractEventRecord=${this.remainderContractEventRecord ? Array.from(this.remainderContractEventRecord).map(r => r.toString()) : 'null'}` +
               `, chainlength=${this.chainlength}]`;
    }
}