export class MultiSignBy {
    private tokenid: string;
    private tokenindex: number;
    private address: string;
    private publickey: string;
    private signature: string;

    constructor(tokenid: string = "", tokenindex: number = 0, address: string = "", publickey: string = "", signature: string = "") {
        this.tokenid = tokenid;
        this.tokenindex = tokenindex;
        this.address = address;
        this.publickey = publickey;
        this.signature = signature;
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

    getAddress(): string {
        return this.address;
    }

    setAddress(address: string): void {
        this.address = address;
    }

    getPublickey(): string {
        return this.publickey;
    }

    setPublickey(publickey: string): void {
        this.publickey = publickey;
    }

    getSignature(): string {
        return this.signature;
    }

    setSignature(signature: string): void {
        this.signature = signature;
    }
}
