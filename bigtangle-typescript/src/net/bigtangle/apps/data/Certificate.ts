// TypeScript translation of Certificate.java

export interface Certificate {
    description?: string;
    type?: string;
    countrycode?: string;
    filename?: string;
    file?: Uint8Array;
}

// --- Functions translated from Java ---
export class CertificateClass {
    description?: string;
    type?: string;
    countrycode?: string;
    filename?: string;
    file?: Uint8Array;

    getDescription(): string | undefined {
        return this.description;
    }
    setDescription(description: string): void {
        this.description = description;
    }
    getFile(): Uint8Array | undefined {
        return this.file;
    }
    setFile(file: Uint8Array): void {
        this.file = file;
    }
    getFilename(): string | undefined {
        return this.filename;
    }
    setFilename(filename: string): void {
        this.filename = filename;
    }
    getType(): string | undefined {
        return this.type;
    }
    setType(type: string): void {
        this.type = type;
    }
    getCountrycode(): string | undefined {
        return this.countrycode;
    }
    setCountrycode(countrycode: string): void {
        this.countrycode = countrycode;
    }

    // --- Serialization and parsing methods translated from Java ---
    toByteArray(): Uint8Array {
        // NOTE: This is a simplified version. For full compatibility, use a binary serialization library.
        const desc = this.description ?? '';
        const type = this.type ?? '';
        const country = this.countrycode ?? '';
        const filename = this.filename ?? '';
        const file = this.file ? Array.from(this.file) : [];
        // Simple JSON-based serialization for demonstration
        const obj = { desc, type, country, filename, file };
        return new TextEncoder().encode(JSON.stringify(obj));
    }

    static parse(buf: Uint8Array): CertificateClass {
        // NOTE: This is a simplified version. For full compatibility, use a binary serialization library.
        const json = new TextDecoder().decode(buf);
        const obj = JSON.parse(json);
        const cert = new CertificateClass();
        cert.description = obj.desc;
        cert.type = obj.type;
        cert.countrycode = obj.country;
        cert.filename = obj.filename;
        cert.file = obj.file ? new Uint8Array(obj.file) : undefined;
        return cert;
    }
}
