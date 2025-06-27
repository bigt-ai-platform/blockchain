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
            const jsonStr = Json.jsonmapper().writeValueAsString(this);
            return new TextEncoder().encode(jsonStr);
        } catch (e: any) {
            throw new Error(e);
        }
    }

    public static parse(buf: Uint8Array): TokenKeyValues {
        const jsonStr = new TextDecoder('utf-8').decode(buf);
        return Json.jsonmapper().readValue(jsonStr, TokenKeyValues);
    }

    public getKeyvalues(): KeyValue[] | null {
        return this.keyvalues;
    }
}