import { Utils } from '../utils/Utils';

export class MultiSign {
    private id: string | null = null;
    private tokenid: string | null = null;
    private tokenindex: number = 0;
    private blockbytes: Uint8Array | null = null;
    private address: string | null = null;
    private sign: number = 0;

    public getBlockhashHex(): string {
        if (this.blockbytes === null) {
            return "";
        }
        return Utils.HEX.encode(this.blockbytes);
    }
    
    public setBlockhashHex(blockhashHex: string | null): void {
        if (blockhashHex === null) {
            this.blockbytes = null;
        } else {
            this.blockbytes = Utils.HEX.decode(blockhashHex);
        }
    }

    public getSign(): number {
        return this.sign;
    }

    public setSign(sign: number): void {
        this.sign = sign;
    }

    public getId(): string | null {
        return this.id;
    }

    public setId(id: string | null): void {
        this.id = id;
    }

    public getTokenid(): string | null {
        return this.tokenid;
    }

    public setTokenid(tokenid: string | null): void {
        this.tokenid = tokenid;
    }

    public getTokenindex(): number {
        return this.tokenindex;
    }

    public setTokenindex(tokenindex: number): void {
        this.tokenindex = tokenindex;
    }

    public getBlockbytes(): Uint8Array | null {
        return this.blockbytes;
    }

    public setBlockbytes(blockbytes: Uint8Array | null): void {
        this.blockbytes = blockbytes;
    }

    public getAddress(): string | null {
        return this.address;
    }

    public setAddress(address: string | null): void {
        this.address = address;
    }

    public toString(): string {
        return `MultiSign [id=${this.id}, tokenid=${this.tokenid}, tokenindex=${this.tokenindex}, blockbytes=${this.blockbytes ? Utils.HEX.encode(this.blockbytes) : 'null'}, address=${this.address}, sign=${this.sign}]`;
    }
}