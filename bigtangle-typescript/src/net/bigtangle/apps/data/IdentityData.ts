// TypeScript translation of IdentityData.java
import type { IdentityCore } from './IdentityCore';
import { Base58 } from '../../utils/Base58';

export interface IdentityData {
    code?: string;
    identificationnumber?: string;
    signatureofholder?: Uint8Array;
    photo?: Uint8Array;
    machinereadable?: Uint8Array;
    identityCoreEn?: IdentityCore;
    identityCore?: IdentityCore;
}

// --- Functions translated from Java ---
export class IdentityDataClass {
    code?: string;
    identificationnumber?: string;
    signatureofholder?: Uint8Array;
    photo?: Uint8Array;
    machinereadable?: Uint8Array;
    identityCoreEn?: any;
    identityCore?: any;

    getCode(): string | undefined { return this.code; }
    setCode(code: string): void { this.code = code; }
    getIdentificationnumber(): string | undefined { return this.identificationnumber; }
    setIdentificationnumber(identificationnumber: string): void { this.identificationnumber = identificationnumber; }
    getSignatureofholder(): Uint8Array | undefined { return this.signatureofholder; }
    setSignatureofholder(signatureofholder: Uint8Array): void { this.signatureofholder = signatureofholder; }
    getPhoto(): Uint8Array | undefined { return this.photo; }
    setPhoto(photo: Uint8Array): void { this.photo = photo; }
    getMachinereadable(): Uint8Array | undefined { return this.machinereadable; }
    setMachinereadable(machinereadable: Uint8Array): void { this.machinereadable = machinereadable; }
    getIdentityCoreEn(): any { return this.identityCoreEn; }
    setIdentityCoreEn(identityCoreEn: any): void { this.identityCoreEn = identityCoreEn; }
    getIdentityCore(): any { return this.identityCore; }
    setIdentityCore(identityCore: any): void { this.identityCore = identityCore; }

    uniqueNameIdentity(): string {
        if (!this.identificationnumber) return '';
        // TODO: Use a real sha256hash160 implementation
        // Placeholder: just base58 encode the utf-8 bytes
        return Base58.encode(new TextEncoder().encode(this.identificationnumber.trim()));
    }
}
