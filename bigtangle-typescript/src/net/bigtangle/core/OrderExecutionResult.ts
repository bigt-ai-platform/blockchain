import { Sha256Hash } from './Sha256Hash';
import { Transaction } from './Transaction';
import { OrderRecord } from './OrderRecord';
import { Spent } from './Spent';
import { TradePair } from '../ordermatch/TradePair';
import { OrderBookEvents } from '../ordermatch/OrderBookEvents';
import { Utils } from '../utils/Utils';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class OrderExecutionResult extends Spent {
    public prevblockhash: Sha256Hash | null = null;
    public chainlength: number = 0;
    public referencedBlocks: Set<Sha256Hash> = new Set();
    public outputTxHash: Sha256Hash | null = null;
    public cancelRecords: Set<Sha256Hash> = new Set();
    public remainderRecords: Set<Sha256Hash> = new Set();

    public outputTx: Transaction | null = null;
    public remainderOrderRecord: OrderRecord[] | null = null;
    public tokenId2Events: Map<TradePair, OrderBookEvents.Event[]> | null = null;

    constructor(
        outputTxHash?: Sha256Hash,
        outputTx?: Transaction,
        prevblockhash?: Sha256Hash,
        cancelRecords?: Set<Sha256Hash>,
        remainderRecords?: Set<Sha256Hash>,
        inserttime?: number,
        remainderOrderRecord?: OrderRecord[],
        referencedOrderBlocks?: Set<Sha256Hash>,
        tokenId2Events?: Map<TradePair, OrderBookEvents.Event[]>,
        chainlength?: number
    ) {
        super();
        if (outputTxHash) this.outputTxHash = outputTxHash;
        if (outputTx) this.outputTx = outputTx;
        if (prevblockhash) this.prevblockhash = prevblockhash;
        if (cancelRecords) this.cancelRecords = cancelRecords;
        if (remainderRecords) this.remainderRecords = remainderRecords;
        if (inserttime) this.setTime(inserttime);
        if (remainderOrderRecord) this.remainderOrderRecord = remainderOrderRecord;
        if (referencedOrderBlocks) this.referencedBlocks = referencedOrderBlocks;
        if (tokenId2Events) this.tokenId2Events = tokenId2Events;
        if (chainlength) this.chainlength = chainlength;
    }

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream(baos);
        try {
            dos.write(super.toByteArray());

            dos.writeBytes(this.outputTxHash ? this.outputTxHash.getBytes() : Sha256Hash.ZERO_HASH.getBytes());
            dos.writeBytes(this.prevblockhash ? this.prevblockhash.getBytes() : Sha256Hash.ZERO_HASH.getBytes());
            dos.writeLong(this.chainlength);

            dos.writeInt(this.cancelRecords.size);
            for (const c of this.cancelRecords) {
                dos.writeBytes(c.getBytes());
            }
            dos.writeInt(this.remainderRecords.size);
            for (const c of this.remainderRecords) {
                dos.writeBytes(c.getBytes());
            }
            dos.writeInt(this.referencedBlocks.size);
            for (const c of this.referencedBlocks) {
                dos.writeBytes(c.getBytes());
            }

            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }

    public parseDIS(dis: DataInputStream): OrderExecutionResult {
        super.parseDIS(dis);

        this.outputTxHash = Sha256Hash.wrap(dis.readBytes());
        this.prevblockhash = Sha256Hash.wrap(dis.readBytes());
        this.chainlength = dis.readLong();

        this.cancelRecords = new Set();
        let cancelRecordsSize = dis.readInt();
        for (let i = 0; i < cancelRecordsSize; i++) {
            this.cancelRecords.add(Sha256Hash.wrap(dis.readBytes()));
        }
        this.remainderRecords = new Set();
        let remainderRecordsSize = dis.readInt();
        for (let i = 0; i < remainderRecordsSize; i++) {
            this.remainderRecords.add(Sha256Hash.wrap(dis.readBytes()));
        }
        let blocksSize = dis.readInt();
        this.referencedBlocks = new Set();
        for (let i = 0; i < blocksSize; i++) {
            this.referencedBlocks.add(Sha256Hash.wrap(dis.readBytes()));
        }

        return this;
    }

    public parseChecked(buf: Uint8Array): OrderExecutionResult {
        try {
            return this.parse(buf);
        } catch (e: any) {
            throw new Error(e);
        }
    }

    public parse(buf: Uint8Array): OrderExecutionResult {
        const bain = new DataInputStream(buf);
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

    public setOutputTxHash(outputTxHash: Sha256Hash | null): void {
        this.outputTxHash = outputTxHash;
    }

    public getOutputTx(): Transaction | null {
        return this.outputTx;
    }

    public setOutputTx(outputTx: Transaction | null): void {
        this.outputTx = outputTx;
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

    public getRemainderOrderRecord(): OrderRecord[] | null {
        return this.remainderOrderRecord;
    }

    public setRemainderOrderRecord(remainderOrderRecord: OrderRecord[] | null): void {
        this.remainderOrderRecord = remainderOrderRecord;
    }

    public getReferencedBlocks(): Set<Sha256Hash> {
        return this.referencedBlocks;
    }

    public setReferencedBlocks(referencedBlocks: Set<Sha256Hash>): void {
        this.referencedBlocks = referencedBlocks;
    }

    public getTokenId2Events(): Map<TradePair, OrderBookEvents.Event[]> | null {
        return this.tokenId2Events;
    }

    public setTokenId2Events(tokenId2Events: Map<TradePair, OrderBookEvents.Event[]> | null): void {
        this.tokenId2Events = tokenId2Events;
    }

    public getChainlength(): number {
        return this.chainlength;
    }

    public setChainlength(chainlength: number): void {
        this.chainlength = chainlength;
    }

    public toString(): string {
        return ` [prevblockhash=${this.prevblockhash}` +
               `, referencedBlocks=${Array.from(this.referencedBlocks).map(h => h.toString())}` +
               `, outputTxHash=${this.outputTxHash}, cancelRecords=${Array.from(this.cancelRecords).map(h => h.toString())}` +
               `, remainderRecords=${Array.from(this.remainderRecords).map(h => h.toString())}, outputTx=${this.outputTx}` +
               `, remainderOrderRecord=${this.remainderOrderRecord ? this.remainderOrderRecord.map(r => r.toString()) : 'null'}` +
               `, tokenId2Events=${this.tokenId2Events ? Array.from(this.tokenId2Events.entries()).map(([k, v]) => `[${k.toString()}, ${v.map(e => e.toString())}]`) : 'null'}` +
               `, chainlength=${this.chainlength}]`;
    }
}