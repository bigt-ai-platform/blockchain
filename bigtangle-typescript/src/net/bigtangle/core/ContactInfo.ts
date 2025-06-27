import { DataClass } from './DataClass';
import { Contact } from './Contact';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class ContactInfo extends DataClass {
    private contactList: Contact[] = [];

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream();
        try {
            dos.write(Buffer.from(super.toByteArray()));
            dos.writeInt(this.contactList.length);
            for (const c of this.contactList) {
                dos.write(Buffer.from(c.toByteArray()));
            }
            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }

    public parseDIS(dis: DataInputStream): ContactInfo {
        super.parseDIS(dis);
        this.contactList = [];
        const size = dis.readInt();
        for (let i = 0; i < size; i++) {
            this.contactList.push(new Contact().parseDIS(dis));
        }
        return this;
    }

    public parse(buf: Uint8Array): ContactInfo {
        const bain = new DataInputStream(Buffer.from(buf));
        try {
            this.parseDIS(bain);
            bain.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return this;
    }

    public getContactList(): Contact[] {
        return this.contactList;
    }

    public setContactList(contactList: Contact[]): void {
        this.contactList = contactList;
    }
}
