import { KeyValue } from './KeyValue';
import { Json } from './utils/Json';

export class TokenKeyValues {
    keyvalues: KeyValue[];

    constructor() {
        this.keyvalues = [];
    }

    addKeyvalue(kv: KeyValue): void {
        if (this.keyvalues == null) {
            this.keyvalues = [];
            this.keyvalues.push(kv);
        } else {
            this.keyvalues.push(kv);
        }
    }

    toByteArray(): Uint8Array {
        try {
            const jsonStr = Json.jsonmapper().writeValueAsString(this);
            return new TextEncoder().encode(jsonStr);
        } catch (e: any) {
            throw new Error(e);
        }
    }

    static parse(buf: Uint8Array): TokenKeyValues {
        const jsonStr = new TextDecoder().decode(buf);
        return Json.jsonmapper().readValue(jsonStr, TokenKeyValues);
    }

    getKeyvalues(): KeyValue[] {
        return this.keyvalues;
    }
}
