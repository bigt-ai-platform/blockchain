import { AbstractResponse } from './AbstractResponse';

export class PayMultiSignResponse extends AbstractResponse {
    private success: boolean = false;

    public static create(success: boolean): PayMultiSignResponse {
        const res = new PayMultiSignResponse();
        res.success = success;
        return res;
    }

    public isSuccess(): boolean {
        return this.success;
    }

    public setSuccess(success: boolean): void {
        this.success = success;
    }
}
