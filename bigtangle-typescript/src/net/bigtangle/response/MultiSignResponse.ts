import { AbstractResponse } from './AbstractResponse';
import { MultiSign } from '../core/MultiSign';

export class MultiSignResponse extends AbstractResponse {
    public multiSigns: MultiSign[] | null = null;
    public signCount: number = 0;

    public getMultiSigns(): MultiSign[] | null {
        return this.multiSigns;
    }

    public setMultiSigns(multiSigns: MultiSign[] | null): void {
        this.multiSigns = multiSigns;
    }

    public static createMultiSignResponse(multiSigns: MultiSign[]): MultiSignResponse;
    public static createMultiSignResponse(signCount: number): MultiSignResponse;
    public static createMultiSignResponse(arg: MultiSign[] | number): MultiSignResponse {
        const res = new MultiSignResponse();
        if (Array.isArray(arg)) {
            res.multiSigns = arg;
        } else {
            res.signCount = arg;
        }
        return res;
    }

    public getSignCount(): number {
        return this.signCount;
    }

    public setSignCount(signCount: number): void {
        this.signCount = signCount;
    }
}