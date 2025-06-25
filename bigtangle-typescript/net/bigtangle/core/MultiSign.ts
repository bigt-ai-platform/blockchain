export class MultiSign {
    private tokenid: string;
    private tokenindex: number;
    private blockhashHex: string;

    constructor(tokenid: string, tokenindex: number, blockhashHex: string) {
        this.tokenid = tokenid;
        this.tokenindex = tokenindex;
        this.blockhashHex = blockhashHex;
    }

    getTokenid(): string {
        return this.tokenid;
    }

    setTokenid(tokenid: string): void {
        this.tokenid = tokenid;
    }

    getTokenindex(): number {
        return this.tokenindex;
    }

    setTokenindex(tokenindex: number): void {
        this.tokenindex = tokenindex;
    }

    getBlockhashHex(): string {
        return this.blockhashHex;
    }

    setBlockhashHex(blockhashHex: string): void {
        this.blockhashHex = blockhashHex;
    }
}
