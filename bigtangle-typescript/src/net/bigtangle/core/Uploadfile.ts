import { Utils } from '../utils/Utils';

export class Uploadfile {
    private name: string | null = null;
    private maxsize: number = 0;
    private fileinfo: Uint8Array | null = null;
    private fileinfoHex: string | null = null;

    public getName(): string | null {
        return this.name;
    }

    public setName(name: string | null): void {
        this.name = name;
    }

    public getMaxsize(): number {
        return this.maxsize;
    }

    public setMaxsize(maxsize: number): void {
        this.maxsize = maxsize;
    }

    public getFileinfo(): Uint8Array | null {
        return this.fileinfo;
    }

    public setFileinfo(fileinfo: Uint8Array | null): void {
        this.fileinfo = fileinfo;
        this.fileinfoHex = fileinfo ? Utils.HEX.encode(fileinfo) : null;
    }

    public getFileinfoHex(): string | null {
        return this.fileinfoHex;
    }

    public setFileinfoHex(fileinfoHex: string | null): void {
        this.fileinfoHex = fileinfoHex;
        this.fileinfo = fileinfoHex ? Utils.HEX.decode(fileinfoHex) : null;
    }
}
