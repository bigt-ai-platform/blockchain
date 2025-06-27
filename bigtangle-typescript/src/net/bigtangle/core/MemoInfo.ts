import { KeyValue } from './KeyValue';
import { Json } from '../utils/Json';

/*
 * help to set memo string as key value list
 */
export class MemoInfo {
    public static readonly MEMO = "memo";
    public static readonly ENCRYPT = "SignedData";

    private kv: KeyValue[] | null = null;

    constructor(memo?: string) {
        if (memo) {
            this.kv = [];
            const keyValue = new KeyValue();
            keyValue.setKey(MemoInfo.MEMO);
            keyValue.setValue(memo);
            this.kv.push(keyValue);
        }
    }

    /*
     * add ENCRYPT data as key value
     */
    public addEncryptMemo(memo: string): void {
        if (this.kv === null) {
            this.kv = [];
        }

        const keyValue = new KeyValue();
        keyValue.setKey(MemoInfo.ENCRYPT);
        keyValue.setValue(memo);
        this.kv.push(keyValue);
    }

    public toJson(): string {
        return Json.jsonmapper().writeValueAsString(this);
    }

    public static parse(jsonStr: string | null): MemoInfo | null {
        if (jsonStr === null) {
            return null;
        }
        return Json.jsonmapper().readValue(jsonStr, MemoInfo);
    }

    /*
     * used for display the memo and cutoff maximal to 20 chars
     */
    public static parseToString(jsonStr: string | null): string | null {
        try {
            if (jsonStr === null) {
                return null;
            }
            const m = Json.jsonmapper().readValue(jsonStr, MemoInfo);
            let s = "";
            if (m.getKv()) {
                for (const keyvalue of m.getKv()!) {
                    if (MemoInfo.valueDisplay(keyvalue) !== null && keyvalue.getKey() !== null && keyvalue.getKey() !== "null"
                        && keyvalue.getKey().length > 0) {
                        s += `${keyvalue.getKey()}: ${MemoInfo.valueDisplay(keyvalue)} \n`;
                    }
                }
            }
            return s;
        } catch (e) {
            return jsonStr;
        }
    }

    private static valueDisplay(keyvalue: KeyValue): string | null {
        if (keyvalue.getValue() === null) {
            return "";
        }
        if (keyvalue.getValue().length < 40) {
            return keyvalue.getValue();
        } else {
            return keyvalue.getValue().substring(0, 40) + "...";
        }
    }

    public getKv(): KeyValue[] | null {
        return this.kv;
    }

    public setKv(kv: KeyValue[] | null): void {
        this.kv = kv;
    }
}