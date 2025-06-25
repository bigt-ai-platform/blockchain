export class KeyParameter {
    constructor(public key: Uint8Array) {}

    getKey(): Uint8Array {
        return this.key;
    }
}
