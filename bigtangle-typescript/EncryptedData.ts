export class EncryptedData {
    public initialisationVector: Uint8Array;
    public encryptedBytes: Uint8Array;

    constructor(initialisationVector: Uint8Array, encryptedBytes: Uint8Array) {
        this.initialisationVector = initialisationVector;
        this.encryptedBytes = encryptedBytes;
    }
}
