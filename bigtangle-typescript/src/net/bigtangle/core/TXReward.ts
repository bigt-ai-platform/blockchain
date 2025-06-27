import { SpentBlock } from './SpentBlock';
import { Sha256Hash } from './Sha256Hash';
import { Utils } from '../utils/Utils';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class TXReward extends SpentBlock {
    private prevBlockHash: Sha256Hash | null = null;
    private difficulty: number = 0;
    private chainLength: number = 0;

    constructor(
        hash?: Sha256Hash,
        confirmed?: boolean,
        spent?: boolean,
        prevBlockHash?: Sha256Hash,
        spenderblockhash?: Sha256Hash,
        difficulty?: number,
        chainLength?: number
    ) {
        super();
        if (hash) this.setBlockHash(hash);
        if (confirmed !== undefined) this.setConfirmed(confirmed);
        if (spent !== undefined) this.setSpent(spent);
        if (prevBlockHash) this.prevBlockHash = prevBlockHash;
        if (spenderblockhash) this.setSpenderBlockHash(spenderblockhash);
        if (difficulty !== undefined) this.difficulty = difficulty;
        if (chainLength !== undefined) this.chainLength = chainLength;
    }

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream(baos);
        try {
            dos.write(super.toByteArray());
            dos.writeBytes(this.prevBlockHash ? this.prevBlockHash.getBytes() : Sha256Hash.ZERO_HASH.getBytes());
            dos.writeLong(this.difficulty);
            dos.writeLong(this.chainLength);
            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }

    public parseDIS(dis: DataInputStream): TXReward {
        super.parseDIS(dis);
        this.prevBlockHash = Sha256Hash.wrap(dis.readBytes());
        this.difficulty = dis.readLong();
        this.chainLength = dis.readLong();
        return this;
    }

    public parse(buf: Uint8Array): TXReward {
        const bain = new DataInputStream(buf);
        try {
            this.parseDIS(bain);
            bain.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return this;
    }

    public getDifficulty(): number {
        return this.difficulty;
    }

    public setDifficulty(difficulty: number): void {
        this.difficulty = difficulty;
    }

    public getChainLength(): number {
        return this.chainLength;
    }

    public setChainLength(chainLength: number): void {
        this.chainLength = chainLength;
    }

    public getPrevBlockHash(): Sha256Hash | null {
        return this.prevBlockHash;
    }

    public setPrevBlockHash(prevBlockHash: Sha256Hash | null): void {
        this.prevBlockHash = prevBlockHash;
    }

    public toString(): string {
        return `TXReward [prevBlockHash=${this.prevBlockHash}, \n difficulty=${this.difficulty}, \n chainLength=` +
               `${this.chainLength}]`;
    }

    public hashCode(): number {
        let result = super.hashCode();
        result = 31 * result + (this.prevBlockHash ? this.prevBlockHash.hashCode() : 0);
        result = 31 * result + this.difficulty;
        result = 31 * result + this.chainLength;
        return result;
    }

    public equals(obj: any): boolean {
        if (this === obj) return true;
        if (!super.equals(obj)) return false;
        if (this.constructor !== obj.constructor) return false;
        const other = obj as TXReward;
        return this.chainLength === other.chainLength && this.difficulty === other.difficulty &&
               (this.prevBlockHash === other.prevBlockHash || (this.prevBlockHash !== null && other.prevBlockHash !== null && this.prevBlockHash.equals(other.prevBlockHash)));
    }
}