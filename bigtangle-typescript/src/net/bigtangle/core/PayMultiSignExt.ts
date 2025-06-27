import { PayMultiSign } from './PayMultiSign';

export class PayMultiSignExt extends PayMultiSign {
    private sign: number = 0;
    private realSignnumber: number = 0;

    public getSign(): number {
        return this.sign;
    }

    public setSign(sign: number): void {
        this.sign = sign;
    }

    public getRealSignnumber(): number {
        return this.realSignnumber;
    }

    public setRealSignnumber(realSignnumber: number): void {
        this.realSignnumber = realSignnumber;
    }
}