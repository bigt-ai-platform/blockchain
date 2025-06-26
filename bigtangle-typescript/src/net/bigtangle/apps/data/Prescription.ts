// TypeScript translation of Prescription.java
import type { Coin } from '../../core/Coin';

export interface Prescription {
    prescription?: string;
    type?: string;
    countrycode?: string;
    filename?: string;
    file?: Uint8Array;
    coins?: Coin[];
}

// --- Functions translated from Java ---
export class PrescriptionClass {
    prescription?: string;
    type?: string;
    countrycode?: string;
    filename?: string;
    file?: Uint8Array;
    coins?: any[];

    getPrescription(): string | undefined { return this.prescription; }
    setPrescription(prescription: string): void { this.prescription = prescription; }
    getFile(): Uint8Array | undefined { return this.file; }
    setFile(file: Uint8Array): void { this.file = file; }
    getFilename(): string | undefined { return this.filename; }
    setFilename(filename: string): void { this.filename = filename; }
    getCoins(): any[] | undefined { return this.coins; }
    setCoins(coins: any[]): void { this.coins = coins; }
    getType(): string | undefined { return this.type; }
    setType(type: string): void { this.type = type; }
    getCountrycode(): string | undefined { return this.countrycode; }
    setCountrycode(countrycode: string): void { this.countrycode = countrycode; }

    // --- Serialization and parsing methods translated from Java ---
    toByteArray(): Uint8Array {
        // NOTE: This is a simplified version. For full compatibility, use a binary serialization library.
        const prescription = this.prescription ?? '';
        const type = this.type ?? '';
        const countrycode = this.countrycode ?? '';
        const filename = this.filename ?? '';
        const file = this.file ? Array.from(this.file) : [];
        const coins = this.coins ? this.coins.map((c: any) => ({ value: c.value, tokenid: c.tokenid })) : [];
        const obj = { prescription, type, countrycode, filename, file, coins };
        return new TextEncoder().encode(JSON.stringify(obj));
    }

    static parse(buf: Uint8Array): PrescriptionClass {
        // NOTE: This is a simplified version. For full compatibility, use a binary serialization library.
        const json = new TextDecoder().decode(buf);
        const obj = JSON.parse(json);
        const pres = new PrescriptionClass();
        pres.prescription = obj.prescription;
        pres.type = obj.type;
        pres.countrycode = obj.countrycode;
        pres.filename = obj.filename;
        pres.file = obj.file ? new Uint8Array(obj.file) : undefined;
        pres.coins = obj.coins;
        return pres;
    }
}
