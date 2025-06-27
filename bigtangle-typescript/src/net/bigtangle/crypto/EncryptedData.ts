/**
 * <p>An instance of EncryptedData is a holder for an initialization vector and encrypted bytes. It is typically
 * used to hold encrypted private key bytes.</p>
 *
 * <p>The initialisation vector is random data that is used to initialise the AES block cipher when the
 * private key bytes were encrypted. You need these for decryption.</p>
 */
export class EncryptedData {
    public readonly initialisationVector: Uint8Array;
    public readonly encryptedBytes: Uint8Array;

    constructor(initialisationVector: Uint8Array, encryptedBytes: Uint8Array) {
        this.initialisationVector = new Uint8Array(initialisationVector);
        this.encryptedBytes = new Uint8Array(encryptedBytes);
    }

    public equals(o: any): boolean {
        if (this === o) return true;
        if (o == null || this.constructor !== o.constructor) return false;
        const other = o as EncryptedData;
        return this.encryptedBytes.every((value, index) => value === other.encryptedBytes[index]) &&
               this.initialisationVector.every((value, index) => value === other.initialisationVector[index]);
    }

    public hashCode(): number {
        let result = 1;
        for (const byte of this.encryptedBytes) {
            result = 31 * result + byte;
        }
        for (const byte of this.initialisationVector) {
            result = 31 * result + byte;
        }
        return result;
    }

    public toString(): string {
        return `EncryptedData [initialisationVector=${this.initialisationVector}, encryptedPrivateKey=${this.encryptedBytes}]`;
    }
}
