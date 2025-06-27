import { Utils } from '../utils/Utils';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class MyHomeAddress {
    private country: string | null = null;
    private province: string | null = null;
    private city: string | null = null;
    private street: string | null = null;
    private email: string | null = null;
    private remark: string | null = null;

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream(baos);
        try {
            const writeOptionalString = (str: string | null) => {
                dos.writeBoolean(str !== null);
                if (str !== null) {
                    const bytes = new TextEncoder().encode(str);
                    dos.writeInt(bytes.length);
                    dos.write(bytes);
                }
            };

            writeOptionalString(this.country);
            writeOptionalString(this.province);
            writeOptionalString(this.city);
            writeOptionalString(this.street);
            writeOptionalString(this.email);
            writeOptionalString(this.remark);
            
            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }

    public parse(buf: Uint8Array): MyHomeAddress {
        const bain = new DataInputStream(buf);
        try {
            const readOptionalString = (): string | null => {
                const hasValue = bain.readBoolean();
                if (hasValue) {
                    const length = bain.readInt();
                    const bytes = bain.readBytes(length);
                    return new TextDecoder('utf-8').decode(bytes);
                } else {
                    return null;
                }
            };

            this.country = readOptionalString();
            this.province = readOptionalString();
            this.city = readOptionalString();
            this.street = readOptionalString();
            this.email = readOptionalString();
            this.remark = readOptionalString();
            
            bain.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return this;
    }

    public getCountry(): string | null {
        return this.country;
    }

    public setCountry(country: string | null): void {
        this.country = country;
    }

    public getProvince(): string | null {
        return this.province;
    }

    public setProvince(province: string | null): void {
        this.province = province;
    }

    public getCity(): string | null {
        return this.city;
    }

    public setCity(city: string | null): void {
        this.city = city;
    }

    public getStreet(): string | null {
        return this.street;
    }

    public setStreet(street: string | null): void {
        this.street = street;
    }

    public getEmail(): string | null {
        return this.email;
    }

    public setEmail(email: string | null): void {
        this.email = email;
    }

    public getRemark(): string | null {
        return this.remark;
    }

    public setRemark(remark: string | null): void {
        this.remark = remark;
    }
}
