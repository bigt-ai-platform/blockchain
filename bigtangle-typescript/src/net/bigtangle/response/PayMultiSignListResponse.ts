import { AbstractResponse } from './AbstractResponse';
import { PayMultiSignExt } from '../core/PayMultiSignExt';

export class PayMultiSignListResponse extends AbstractResponse {
    private payMultiSigns: PayMultiSignExt[] | null = null;

    public static create(payMultiSigns: PayMultiSignExt[]): PayMultiSignListResponse {
        const res = new PayMultiSignListResponse();
        res.payMultiSigns = payMultiSigns;
        return res;
    }

    public getPayMultiSigns(): PayMultiSignExt[] | null {
        return this.payMultiSigns;
    }
}
