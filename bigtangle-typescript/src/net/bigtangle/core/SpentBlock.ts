import { DataClass } from './DataClass';
import { Sha256Hash } from './Sha256Hash';
import { Utils } from '../utils/Utils';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class SpentBlock extends DataClass {
    private blockHash: Sha256Hash | null = null;
    private confirmed: boolean = false;
    private spent: boolean = false;
    private spenderBlockHash: Sha256Hash | null = null;
    private time: number = 0;

    public setDefault(): void {
        this.spent = false;
        this.confirmed = false;
        this.spenderBlockHash = null;
        this.time = Math.floor(Date.now() / 1000);
    }

    public getBlockHashHex(): string {
        return this.blockHash !== null ? Utils.HEX.encode(this.blockHash.getBytes()) : "";
    }

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream(baos);
        try {
            dos.write(super.toByteArray());
            dos.writeBytes(this.blockHash === null ? Sha256Hash.ZERO_HASH.getBytes() : this.blockHash.getBytes());
            dos.writeBoolean(this.confirmed);
            dos.writeBoolean(this.spent);
            dos.writeBytes(this.spenderBlockHash === null ? Sha256Hash.ZERO_HASH.getBytes() : this.spenderBlockHash.getBytes());
            dos.writeLong(this.time);
            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }

    public parseDIS(dis: DataInputStream): SpentBlock {
        super.parseDIS(dis);
        this.blockHash = Sha256Hash.wrap(dis.readBytes());
        this.confirmed = dis.readBoolean();
        this.spent = dis.readBoolean();
        this.spenderBlockHash = Sha256Hash.wrap(dis.readBytes());
        if (this.spenderBlockHash.equals(Sha256Hash.ZERO_HASH)) {
            this.spenderBlockHash = null;
        }
        this.time = dis.readLong();
        return this;
    }

    public parse(buf: Uint8Array): SpentBlock {
        const bain = new DataInputStream(buf);
        try {
            this.parseDIS(bain);
            bain.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return this;
    }

    public getBlockHash(): Sha256Hash | null {
        return this.blockHash;
    }

    public setBlockHash(blockHash: Sha256Hash | null): void {
        this.blockHash = blockHash;
    }

    public isConfirmed(): boolean {
        return this.confirmed;
    }

    public setConfirmed(confirmed: boolean): void {
        this.confirmed = confirmed;
    }

    public isSpent(): boolean {
        return this.spent;
    }

    public setSpent(spent: boolean): void {
        this.spent = spent;
    }

    public getSpenderBlockHash(): Sha256Hash | null {
        return this.spenderBlockHash;
    }

    public setSpenderBlockHash(spenderBlockHash: Sha256Hash | null): void {
        this.spenderBlockHash = spenderBlockHash;
    }

    public getTime(): number {
        return this.time;
    }

    public setTime(time: number): void {
        this.time = time;
    }

    public hashCode(): number {
        let result = 17;
        result = 31 * result + (this.blockHash ? this.blockHash.hashCode() : 0);
        result = 31 * result + (this.confirmed ? 1 : 0);
        result = 31 * result + (this.spent ? 1 : 0);
        result = 31 * result + (this.spenderBlockHash ? this.spenderBlockHash.hashCode() : 0);
        result = 31 * result + this.time;
        return result;
    }

    public equals(obj: any): boolean {
        if (this === obj) return true;
        if (obj == null || this.constructor !== obj.constructor) return false;
        const other = obj as SpentBlock;
        return (this.blockHash === other.blockHash || (this.blockHash !== null && other.blockHash !== null && this.blockHash.equals(other.blockHash))) &&
               this.confirmed === other.confirmed &&
               (this.spenderBlockHash === other.spenderBlockHash || (this.spenderBlockHash !== null && other.spenderBlockHash !== null && this.spenderBlockHash.equals(other.spenderBlockHash))) &&
               this.spent === other.spent &&
               this.time === other.time;
    }

    public toString(): string {
        return ` [blockHash=${this.blockHash}, confirmed=${this.confirmed}, spent=${this.spent}` +
               `, spenderBlockHash=${this.spenderBlockHash}, time=${this.time}]`;
    }
}