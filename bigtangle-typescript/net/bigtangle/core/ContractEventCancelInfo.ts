import { Sha256Hash } from './Sha256Hash';

export class ContractEventCancelInfo {
    private eventblockhash: Sha256Hash;

    constructor(eventblockhash: Sha256Hash) {
        this.eventblockhash = eventblockhash;
    }

    toByteArray(): Uint8Array {
        // Simplified implementation for now.
        // In a real scenario, this would serialize the object to a byte array.
        return new Uint8Array();
    }
}
