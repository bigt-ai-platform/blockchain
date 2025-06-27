import { AbstractResponse } from './AbstractResponse';

export class LongResponse extends AbstractResponse {
    private value: number | null = null;

    public static create(value: number): LongResponse {
        const res = new LongResponse();
        res.value = value;
        return res;
    }

    public getValue(): number | null {
        return this.value;
    }

    public setValue(value: number | null): void {
        this.value = value;
    }
}
