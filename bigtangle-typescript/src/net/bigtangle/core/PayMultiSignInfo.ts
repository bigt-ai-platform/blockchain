import { PayMultiSign } from './PayMultiSign';
import { PayMultiSignAddress } from './PayMultiSignAddress';

export class PayMultiSignInfo {
    private payMultiSign: PayMultiSign | null = null;
    private payMultiSignAddresses: PayMultiSignAddress[] = [];

    public getPayMultiSign(): PayMultiSign | null {
        return this.payMultiSign;
    }

    public setPayMultiSign(payMultiSign: PayMultiSign | null): void {
        this.payMultiSign = payMultiSign;
    }

    public getPayMultiSignAddresses(): PayMultiSignAddress[] {
        return this.payMultiSignAddresses;
    }

    public setPayMultiSignAddresses(payMultiSignAddresses: PayMultiSignAddress[]): void {
        this.payMultiSignAddresses = payMultiSignAddresses;
    }
}
