import { Utils } from './Utils';
import { DataInputStream } from './utils/DataInputStream';
import { DataOutputStream } from './utils/DataOutputStream';

export class KeyValue {
    key: string;
    value: string;

    constructor(key: string = '', value: string = '') {
        this.key = key;
        this.value = value;
    }

    getKey(): string {
        return this.key;
    }

    setKey(key: string): void {
        this.key = key;
    }

    getValue(): string {
        return this.value;
    }

    setValue(value: string): void {
        this.value = value;
    }

    toByteArray(): Uint8Array {
        const baos = new DataOutputStream();
        try {
            Utils.writeNBytesString(baos, this.key);
            Utils.writeNBytesString(baos, this.value);
            return baos.toByteArray();
        } catch (e: any) {
            throw new Error(e);
        }
    }

    parseDIS(dis: DataInputStream): KeyValue {
        this.key = Utils.readNBytesString(dis);
        this.value = Utils.readNBytesString(dis);
        return this;
    }

    parse(buf: Uint8Array): KeyValue {
        const bain = new DataInputStream(buf);
        this.parseDIS(bain);
        return this;
    }
}
