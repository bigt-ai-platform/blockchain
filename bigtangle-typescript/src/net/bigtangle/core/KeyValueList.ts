import { DataClass } from './DataClass';
import { KeyValue } from './KeyValue';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class KeyValueList extends DataClass {
    private keyvalues: KeyValue[] = [];

    public addKeyvalue(kv: KeyValue): void {
        this.keyvalues.push(kv);
    }

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream(baos);
        try {
            dos.write(super.toByteArray());
            dos.writeInt(this.keyvalues.length);
            for (const c of this.keyvalues) {
                dos.write(c.toByteArray());
            }
            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }

    public parseDIS(dis: DataInputStream): KeyValueList {
        super.parseDIS(dis);
        this.keyvalues = [];
        const size = dis.readInt();
        for (let i = 0; i < size; i++) {
            this.keyvalues.push(new KeyValue().parseDIS(dis));
        }
        return this;
    }

    public parse(buf: Uint8Array): KeyValueList {
        const bain = new DataInputStream(buf);
        try {
            this.parseDIS(bain);
            bain.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return this;
    }

    public getKeyvalues(): KeyValue[] {
        return this.keyvalues;
    }

    public setKeyvalues(keyvalues: KeyValue[]): void {
        this.keyvalues = keyvalues;
    }
}
