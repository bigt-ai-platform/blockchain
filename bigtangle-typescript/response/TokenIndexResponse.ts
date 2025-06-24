export class TokenIndexResponse {
    private tokenindex: number;
    private blockhash: string; // Assuming blockhash is a hex string

    constructor(tokenindex: number, blockhash: string) {
        this.tokenindex = tokenindex;
        this.blockhash = blockhash;
    }

    getTokenindex(): number {
        return this.tokenindex;
    }

    setTokenindex(tokenindex: number): void {
        this.tokenindex = tokenindex;
    }

    getBlockhash(): string {
        return this.blockhash;
    }

    setBlockhash(blockhash: string): void {
        this.blockhash = blockhash;
    }
}
