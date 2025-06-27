import { BigInteger } from './BigInteger';

export class PayMultiSign {
    private orderid: string | null = null;
    private tokenid: string | null = null;
    private toaddress: string | null = null;
    private blockhashHex: string | null = null;
    private blockhash: Uint8Array | null = null;
    private amount: BigInteger | null = null;
    private minsignnumber: number = 0;
    private pubKeyHex: string | null = null;
    private outputHashHex: string | null = null;
    private outputindex: number = 0;
    private sign: number = 0;
    private signcount: number = 0;

    public getOutputHashHex(): string | null {
        return this.outputHashHex;
    }

    public setOutputHashHex(outputHashHex: string | null): void {
        this.outputHashHex = outputHashHex;
    }

    public getPubKeyHex(): string | null {
        return this.pubKeyHex;
    }

    public setPubKeyHex(pubKeyHex: string | null): void {
        this.pubKeyHex = pubKeyHex;
    }

    public getOrderid(): string | null {
        return this.orderid;
    }

    public setOrderid(orderid: string | null): void {
        this.orderid = orderid;
    }

    public getTokenid(): string | null {
        return this.tokenid;
    }

    public setTokenid(tokenid: string | null): void {
        this.tokenid = tokenid;
    }

    public getToaddress(): string | null {
        return this.toaddress;
    }

    public setToaddress(toaddress: string | null): void {
        this.toaddress = toaddress;
    }

    public getAmount(): BigInteger | null {
        return this.amount;
    }

    public setAmount(amount: BigInteger | null): void {
        this.amount = amount;
    }

    public getMinsignnumber(): number {
        return this.minsignnumber;
    }

    public setMinsignnumber(minsignnumber: number): void {
        this.minsignnumber = minsignnumber;
    }

    public getBlockhashHex(): string | null {
        return this.blockhashHex;
    }

    public setBlockhashHex(blockhashHex: string | null): void {
        this.blockhashHex = blockhashHex;
    }

    public getBlockhash(): Uint8Array | null {
        return this.blockhash;
    }

    public setBlockhash(blockhash: Uint8Array | null): void {
        this.blockhash = blockhash;
    }

    public getOutputindex(): number {
        return this.outputindex;
    }

    public setOutputindex(outputindex: number): void {
        this.outputindex = outputindex;
    }

    public getSign(): number {
        return this.sign;
    }

    public setSign(sign: number): void {
        this.sign = sign;
    }

    public getSigncount(): number {
        return this.signcount;
    }

    public setSigncount(signcount: number): void {
        this.signcount = signcount;
    }
}