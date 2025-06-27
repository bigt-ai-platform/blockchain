import { Utils } from '../utils/Utils';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class KeyValue {
    private key: string;
    private value: string;

    constructor(key: string = '', value: string = '') {
        this.key = key;
        this.value = value;
    }

    public getKey(): string {
        return this.key;
    }

    public setKey(key: string): void {
        this.key = key;
    }

    public getValue(): string {
        return this.value;
    }

    public setValue(value: string): void {
        this.value = value;
    }

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream();
        try {
            dos.writeNBytesString(this.key);
            dos.writeNBytesString(this.value);
            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }

    public parseDIS(dis: DataInputStream): KeyValue {
        this.key = dis.readNBytesString() ?? '';
        this.value = dis.readNBytesString() ?? '';
        return this;
    }

    public parse(buf: Uint8Array): KeyValue {
        const bain = new DataInputStream(Buffer.from(buf));
        try {
            this.parseDIS(bain);
            bain.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return this;
    }
}