import { SpentBlock } from './SpentBlock';
import { Sha256Hash } from './Sha256Hash';

export class SpentBlockData extends SpentBlock {
    constructor(blockhash: Sha256Hash, spent: boolean, confirmed: boolean, spenderBlockHash: Sha256Hash) {
        super();
        this.setBlockHash(blockhash);
        this.setSpent(spent);
        this.setSpenderBlockHash(spenderBlockHash);
        this.setConfirmed(confirmed);
    }
}
