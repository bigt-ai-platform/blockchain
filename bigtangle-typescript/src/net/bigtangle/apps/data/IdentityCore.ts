// TypeScript translation of IdentityCore.java

export interface IdentityCore {
    surname?: string;
    forenames?: string;
    nationality?: string;
    dateofbirth?: string;
    sex?: string;
    placeofbirth?: string;
    dateofissue?: string;
    dateofexpiry?: string;
    authority?: string;
}

// --- Functions translated from Java ---
export class IdentityCoreClass {
    surname?: string;
    forenames?: string;
    nationality?: string;
    dateofbirth?: string;
    sex?: string;
    placeofbirth?: string;
    dateofissue?: string;
    dateofexpiry?: string;
    authority?: string;
    nameatbirth?: string;

    getSurname(): string | undefined { return this.surname; }
    setSurname(surname: string): void { this.surname = surname; }
    getForenames(): string | undefined { return this.forenames; }
    setForenames(forenames: string): void { this.forenames = forenames; }
    getNationality(): string | undefined { return this.nationality; }
    setNationality(nationality: string): void { this.nationality = nationality; }
    getDateofbirth(): string | undefined { return this.dateofbirth; }
    setDateofbirth(dateofbirth: string): void { this.dateofbirth = dateofbirth; }
    getSex(): string | undefined { return this.sex; }
    setSex(sex: string): void { this.sex = sex; }
    getPlaceofbirth(): string | undefined { return this.placeofbirth; }
    setPlaceofbirth(placeofbirth: string): void { this.placeofbirth = placeofbirth; }
    getDateofissue(): string | undefined { return this.dateofissue; }
    setDateofissue(dateofissue: string): void { this.dateofissue = dateofissue; }
    getDateofexpiry(): string | undefined { return this.dateofexpiry; }
    setDateofexpiry(dateofexpiry: string): void { this.dateofexpiry = dateofexpiry; }
    getAuthority(): string | undefined { return this.authority; }
    setAuthority(authority: string): void { this.authority = authority; }
    getNameatbirth(): string | undefined { return this.nameatbirth; }
    setNameatbirth(nameatbirth: string): void { this.nameatbirth = nameatbirth; }

    // --- Serialization and parsing methods translated from Java ---
    toByteArray(): Uint8Array {
        // NOTE: This is a simplified version. For full compatibility, use a binary serialization library.
        const surname = this.surname ?? '';
        const forenames = this.forenames ?? '';
        const nationality = this.nationality ?? '';
        const dateofbirth = this.dateofbirth ?? '';
        const sex = this.sex ?? '';
        const placeofbirth = this.placeofbirth ?? '';
        const dateofissue = this.dateofissue ?? '';
        const dateofexpiry = this.dateofexpiry ?? '';
        const authority = this.authority ?? '';
        const nameatbirth = this.nameatbirth ?? '';
        const obj = { surname, forenames, nationality, dateofbirth, sex, placeofbirth, dateofissue, dateofexpiry, authority, nameatbirth };
        return new TextEncoder().encode(JSON.stringify(obj));
    }

    static parse(buf: Uint8Array): IdentityCoreClass {
        // NOTE: This is a simplified version. For full compatibility, use a binary serialization library.
        const json = new TextDecoder().decode(buf);
        const obj = JSON.parse(json);
        const core = new IdentityCoreClass();
        core.surname = obj.surname;
        core.forenames = obj.forenames;
        core.nationality = obj.nationality;
        core.dateofbirth = obj.dateofbirth;
        core.sex = obj.sex;
        core.placeofbirth = obj.placeofbirth;
        core.dateofissue = obj.dateofissue;
        core.dateofexpiry = obj.dateofexpiry;
        core.authority = obj.authority;
        core.nameatbirth = obj.nameatbirth;
        return core;
    }
}
