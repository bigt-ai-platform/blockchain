import { AbstractResponse } from './AbstractResponse';
import { PayMultiSignAddress } from '../core/PayMultiSignAddress';

export class PayMultiSignAddressListResponse extends AbstractResponse {
    private payMultiSignAddresses: PayMultiSignAddress[] | null = null;

    public static create(payMultiSignAddresses: PayMultiSignAddress[]): PayMultiSignAddressListResponse {
        const res = new PayMultiSignAddressListResponse();
        res.payMultiSignAddresses = payMultiSignAddresses;
        return res;
    }

    public getPayMultiSignAddresses(): PayMultiSignAddress[] | null {
        return this.payMultiSignAddresses;
    }

    public setPayMultiSignAddresses(payMultiSignAddresses: PayMultiSignAddress[] | null): void {
        this.payMultiSignAddresses = payMultiSignAddresses;
    }
}
