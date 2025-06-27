import { Sha256Hash } from './Sha256Hash';

export class UserData {
    private blockhash: Sha256Hash | null = null;
    private dataclassname: string | null = null;
    private pubKey: string | null = null;
    private data: Uint8Array | null = null;
    private blocktype: number = 0;

    public getBlocktype(): number {
        return this.blocktype;
    }

    public setBlocktype(blocktype: number): void {
        this.blocktype = blocktype;
    }

    public getBlockhash(): Sha256Hash | null {
        return this.blockhash;
    }

    public setBlockhash(blockhash: Sha256Hash | null): void {
        this.blockhash = blockhash;
    }

    public getDataclassname(): string | null {
        return this.dataclassname;
    }

    public setDataclassname(dataclassname: string | null): void {
        this.dataclassname = dataclassname;
    }

    public getPubKey(): string | null {
        return this.pubKey;
    }

    public setPubKey(pubKey: string | null): void {
        this.pubKey = pubKey;
    }

    public getData(): Uint8Array | null {
        return this.data;
    }

    public setData(data: Uint8Array | null): void {
        this.data = data;
    }
}
