export class MultiSignAddress {
    private tokenid: string;
    private address: string;
    private pubKeyHex: string;
    private tokenindex: number;
    private tokenHolder: number; // Added tokenHolder property

    constructor(tokenid: string, address: string, pubKeyHex: string, tokenindex: number = 0, tokenHolder: number = 0) {
        this.tokenid = tokenid;
        this.address = address;
        this.pubKeyHex = pubKeyHex;
        this.tokenindex = tokenindex;
        this.tokenHolder = tokenHolder; // Initialize tokenHolder
    }

    getTokenid(): string {
        return this.tokenid;
    }

    setTokenid(tokenid: string): void {
        this.tokenid = tokenid;
    }

    getAddress(): string {
        return this.address;
    }

    setAddress(address: string): void {
        this.address = address;
    }

    getPubKeyHex(): string {
        return this.pubKeyHex;
    }

    setPubKeyHex(pubKeyHex: string): void {
        this.pubKeyHex = pubKeyHex;
    }

    getTokenindex(): number {
        return this.tokenindex;
    }

    setTokenindex(tokenindex: number): void {
        this.tokenindex = tokenindex;
    }

    getTokenHolder(): number {
        return this.tokenHolder;
    }

    setTokenHolder(tokenHolder: number): void {
        this.tokenHolder = tokenHolder;
    }
}
