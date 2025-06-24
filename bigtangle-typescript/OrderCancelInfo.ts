import { Sha256Hash } from './Sha256Hash';

export class OrderCancelInfo {
    private orderblockhash: Sha256Hash;

    constructor(orderblockhash: Sha256Hash) {
        this.orderblockhash = orderblockhash;
    }

    toByteArray(): Uint8Array {
        // Simplified implementation for now.
        // In a real scenario, this would serialize the object to a byte array.
        return new Uint8Array();
    }
}
