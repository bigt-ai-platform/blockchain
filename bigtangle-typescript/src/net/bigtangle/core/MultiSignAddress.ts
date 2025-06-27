import { Sha256Hash } from './Sha256Hash';

export class MultiSignAddress {
    private blockhash: Sha256Hash | null = null;
    private tokenid: string | null = null;
    private address: string | null = null;
    private pubKeyHex: string | null = null;
    private posIndex: number = 0;
    private tokenHolder: number = 0;

    public getPosIndex(): number {
        return this.posIndex;
    }

    public setPosIndex(posIndex: number): void {
        this.posIndex = posIndex;
    }

    public getPubKeyHex(): string | null {
        return this.pubKeyHex;
    }

    public setPubKeyHex(pubKeyHex: string | null): void {
        this.pubKeyHex = pubKeyHex;
    }

    public getTokenid(): string | null {
        return this.tokenid;
    }

    public setTokenid(tokenid: string | null): void {
        this.tokenid = tokenid;
    }

    public getAddress(): string | null {
        return this.address;
    }

    public setAddress(address: string | null): void {
        this.address = address;
    }

    public getBlockhash(): Sha256Hash | null {
        return this.blockhash;
    }

    public setBlockhash(blockhash: Sha256Hash | null): void {
        this.blockhash = blockhash;
    }

    public getTokenHolder(): number {
        return this.tokenHolder;
    }

    public setTokenHolder(tokenHolder: number): void {
        this.tokenHolder = tokenHolder;
    }

    constructor(tokenid?: string, address?: string, pubKeyHex?: string, tokenHolder?: number) {
        if (tokenid) this.tokenid = tokenid;
        if (address) this.address = address;
        if (pubKeyHex) this.pubKeyHex = pubKeyHex;
        if (tokenHolder !== undefined) this.tokenHolder = tokenHolder;
    }

    public toString(): string {
        return `MultiSignAddress [blockhash=${this.blockhash}, tokenid=${this.tokenid}, address=${this.address}` +
               `, pubKeyHex=${this.pubKeyHex}, posIndex=${this.posIndex}, tokenHolder=${this.tokenHolder}]`;
    }
}