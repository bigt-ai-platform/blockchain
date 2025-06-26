// TypeScript translation of UUIDUtil.java

export class UUIDUtil {
    static randomUUID(): string {
        // Generates a UUID v4 and removes dashes
        // Use crypto API for secure random UUID
        const uuid = ([1e7] as any + -1e3 + -4e3 + -8e3 + -1e11).replace(/[018]/g, (c: string) =>
            (parseInt(c) ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> (parseInt(c) / 4)).toString(16)
        );
        return uuid.replace(/-/g, '');
    }
}
