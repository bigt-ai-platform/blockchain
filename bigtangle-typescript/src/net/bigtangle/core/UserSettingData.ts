import { Utils } from '../utils/Utils';
import { DataInputStream } from '../utils/DataInputStream';
import { DataOutputStream } from '../utils/DataOutputStream';
import { UnsafeByteArrayOutputStream } from './UnsafeByteArrayOutputStream';

export class UserSettingData {
    private key: string | null = null;
    private value: string | null = null;
    private domain: string | null = null;

    public toByteArray(): Uint8Array {
        const baos = new UnsafeByteArrayOutputStream();
        const dos = new DataOutputStream(baos);
        try {
            dos.writeNBytesString(this.key || "");
            dos.writeNBytesString(this.value || "");
            dos.writeNBytesString(this.domain || "");
            dos.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return baos.toByteArray();
    }
    
    public parseDIS(dis: DataInputStream): UserSettingData {
        this.key = dis.readNBytesString();
        this.value = dis.readNBytesString();
        this.domain = dis.readNBytesString();
        return this;
    }

    public parse(buf: Uint8Array): UserSettingData {
        const bain = new DataInputStream(buf);
        try {
            this.parseDIS(bain);
            bain.close();
        } catch (e: any) {
            throw new Error(e);
        }
        return this;
    }
    
    public getKey(): string | null {
        return this.key;
    }

    public setKey(key: string | null): void {
        this.key = key;
    }

    public getValue(): string | null {
        return this.value;
    }

    public setValue(value: string | null): void {
        this.value = value;
    }

    public getDomain(): string | null {
        return this.domain;
    }

    public setDomain(domain: string | null): void {
        this.domain = domain;
    }
}
