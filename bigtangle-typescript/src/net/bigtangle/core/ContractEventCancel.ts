import { Sha256Hash } from './Sha256Hash';
import { SpentBlock } from './SpentBlock';

/**
 *
 */
export class ContractEventCancel extends SpentBlock {

    // this is the block hash of the Order Block, which should be canceled
    private eventBlockHash: Sha256Hash | null = null;

    constructor(eventBlockHash?: Sha256Hash) {
        super();
        this.setDefault(); 
        if (eventBlockHash) {
            this.eventBlockHash = eventBlockHash;
        }
    }

    public getEventBlockHash(): Sha256Hash | null {
        return this.eventBlockHash;
    }

    public setEventBlockHash(eventBlockHash: Sha256Hash | null): void {
        this.eventBlockHash = eventBlockHash;
    }
}
