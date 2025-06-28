import { KeyValue } from './KeyValue';
import { Json } from '../utils/Json';

export class TokenKeyValues {
    private keyvalues: KeyValue[] | null = null;

    public addKeyvalue(kv: KeyValue): void {
        if (this.keyvalues === null) {
            this.keyvalues = [];
        }
        this.keyvalues.push(kv);
    }

    public toByteArray(): Uint8Array {
        try {
            const jsonStr = JSON.stringify(this);
            return new TextEncoder().encode(jsonStr);
        } catch (e: any) {
            throw new Error(e);
        }
    }

    public static parse(buf: Uint8Array): TokenKeyValues {
        const jsonStr = new TextDecoder('utf-8').decode(buf);
        const obj = JSON.parse(jsonStr);
        const instance = new TokenKeyValues();
        if (obj.keyvalues) {
            instance['keyvalues'] = obj.keyvalues;
        }
        return instance;
    }

    public getKeyvalues(): KeyValue[] | null {
        return this.keyvalues;
    }
}