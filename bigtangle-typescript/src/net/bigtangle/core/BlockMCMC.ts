import { Sha256Hash } from './Sha256Hash';

/*
 * Evaluation of block, variable in time
 */
export class BlockMCMC {
    // Hash of corresponding block
    private blockHash: Sha256Hash | null = null;

    // Percentage of MCMC selected tips approving this block
    private rating: number = 0;

    // Longest path to tip
    private depth: number = 0;

    // Count of indirect approver blocks
    private cumulativeWeight: number = 0;

    constructor(blockHash?: Sha256Hash, rating?: number, depth?: number, cumulativeWeight?: number) {
        if (blockHash !== undefined) {
            this.blockHash = blockHash;
            this.rating = rating !== undefined ? rating : 0;
            this.depth = depth !== undefined ? depth : 0;
            this.cumulativeWeight = cumulativeWeight !== undefined ? cumulativeWeight : 0;
        }
    }

    public static defaultBlockMCMC(blockHash: Sha256Hash): BlockMCMC {
        return new BlockMCMC(blockHash, 0, 0, 1);
    }

    public getBlockHash(): Sha256Hash | null {
        return this.blockHash;
    }

    public setBlockHash(blockHash: Sha256Hash | null): void {
        this.blockHash = blockHash;
    }

    public getRating(): number {
        return this.rating;
    }

    public setRating(rating: number): void {
        this.rating = rating;
    }

    public getDepth(): number {
        return this.depth;
    }

    public setDepth(depth: number): void {
        this.depth = depth;
    }

    public getCumulativeWeight(): number {
        return this.cumulativeWeight;
    }

    public setCumulativeWeight(cumulativeWeight: number): void {
        this.cumulativeWeight = cumulativeWeight;
    }

    public toString(): string {
        return `BlockMCMC [blockHash=${this.blockHash}, rating=${this.rating}, depth=${this.depth}, cumulativeWeight=${this.cumulativeWeight}]`;
    }
}