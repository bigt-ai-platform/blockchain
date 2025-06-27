import { Utils } from '../utils/Utils';

export class PayMultiSignAddress {
    private orderid: string | null = null;
    private pubKey: string | null = null;
    private sign: number = 0;
    private signInputData: Uint8Array | null = null;
    private signIndex: number = 0;

    public getSignIndex(): number {
        return this.signIndex;
    }

    public setSignIndex(signIndex: number): void {
        this.signIndex = signIndex;
    }

    public getSignInputDataHex(): string {
        if (this.signInputData === null) {
            return "";
        }
        return Utils.HEX.encode(this.signInputData);
    }
    
    public getSignInputData(): Uint8Array | null {
        return this.signInputData;
    }

    public setSignInputData(signInputData: Uint8Array | null): void {
        this.signInputData = signInputData;
    }

    public getOrderid(): string | null {
        return this.orderid;
    }

    public setOrderid(orderid: string | null): void {
        this.orderid = orderid;
    }

    public getPubKey(): string | null {
        return this.pubKey;
    }

    public setPubKey(pubKey: string | null): void {
        this.pubKey = pubKey;
    }

    public getSign(): number {
        return this.sign;
    }

    public setSign(sign: number): void {
        this.sign = sign;
    }
}