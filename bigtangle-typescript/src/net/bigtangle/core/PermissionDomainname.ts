import { Utils } from '../utils/Utils';
import { ECKey } from './ECKey';

export class PermissionDomainname {
    private pubKeyHex: string | null = null;
    private priKeyHex: string | null = null;

    constructor(pubKeyHex?: string, priKeyHex?: string) {
        if (pubKeyHex) this.pubKeyHex = pubKeyHex;
        if (priKeyHex) this.priKeyHex = priKeyHex;
    }

    public getPubKeyHex(): string | null {
        return this.pubKeyHex;
    }

    public setPubKeyHex(pubKeyHex: string | null): void {
        this.pubKeyHex = pubKeyHex;
    }

    public getPriKeyHex(): string | null {
        return this.priKeyHex;
    }

    public setPriKeyHex(priKeyHex: string | null): void {
        this.priKeyHex = priKeyHex;
    }

    public getPriKeyBuf(): Uint8Array {
        return this.priKeyHex ? Utils.HEX.decode(this.priKeyHex) : new Uint8Array();
    }

    public getPubKeyBuf(): Uint8Array {
        return this.pubKeyHex ? Utils.HEX.decode(this.pubKeyHex) : new Uint8Array();
    }

    public getOutKey(): ECKey {
        const pubKey = this.getPubKeyBuf();
        const outKey = ECKey.fromPublicOnly(pubKey);
        return outKey;
    }
}