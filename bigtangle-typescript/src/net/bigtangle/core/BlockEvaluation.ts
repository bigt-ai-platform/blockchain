import { Sha256Hash } from './Sha256Hash';
import { Block } from './Block';

/*
 * Evaluation of a block, variable in time and DAG formation and references.
 *   see SolidityState for usage of solid
 */
export class BlockEvaluation {
    // Hash of the block
    private blockHash!: Sha256Hash;
    // height to genesis block
    private height!: number;

    // chain length of reward block as consensus
    private milestone!: number;

    // Timestamp for entry into milestone as true, reset if flip to false
    private milestoneLastUpdateTime!: number;

    // Timestamp for entry into evaluations/reception time
    private insertTime!: number;

    // -milestone: conflict with milestone
    // 0: initial.
    // -1: unsolid 1: solid for calculation
    // 2: solid
    private solid!: number;

    // If true, this block is confirmed by mcmc and milestone
    private confirmed!: boolean;

    constructor() {
    }

    // deep copy constructor
    public static from(other: BlockEvaluation): BlockEvaluation {
        const evaluation = new BlockEvaluation();
        evaluation.setBlockHash(other.blockHash);
        evaluation.setHeight(other.height);
        evaluation.setMilestone(other.milestone);
        evaluation.setMilestoneLastUpdateTime(other.milestoneLastUpdateTime);
        evaluation.setInsertTime(other.insertTime);
        evaluation.setSolid(other.solid);
        evaluation.setConfirmed(other.confirmed);
        return evaluation;
    }

    public static buildInitial(block: Block): BlockEvaluation {
        const currentTimeMillis = Date.now();
        return BlockEvaluation.build(
            block.getHash(),
            0,
            -1,
            currentTimeMillis,
            currentTimeMillis,
            0,
            false
        );
    }

    public static build(
        blockhash: Sha256Hash,
        height: number,
        milestone: number,
        milestoneLastUpdateTime: number,
        insertTime: number,
        solid: number,
        confirmed: boolean
    ): BlockEvaluation {
        const evaluation = new BlockEvaluation();
        evaluation.setBlockHash(blockhash);
        evaluation.setHeight(height);
        evaluation.setMilestone(milestone);
        evaluation.setMilestoneLastUpdateTime(milestoneLastUpdateTime);
        evaluation.setInsertTime(insertTime);
        evaluation.setSolid(solid);
        evaluation.setConfirmed(confirmed);
        return evaluation;
    }

    public getBlockHash(): Sha256Hash {
        return this.blockHash;
    }

    public setBlockHash(blockHash: Sha256Hash): void {
        this.blockHash = blockHash;
    }

    public getHeight(): number {
        return this.height;
    }

    public setHeight(height: number): void {
        this.height = height;
    }

    public getMilestone(): number {
        return this.milestone;
    }

    public setMilestone(milestone: number): void {
        this.milestone = milestone;
    }

    public getMilestoneLastUpdateTime(): number {
        return this.milestoneLastUpdateTime;
    }

    public setMilestoneLastUpdateTime(milestoneLastUpdateTime: number): void {
        this.milestoneLastUpdateTime = milestoneLastUpdateTime;
    }

    public getInsertTime(): number {
        return this.insertTime;
    }

    public setInsertTime(insertTime: number): void {
        this.insertTime = insertTime;
    }

    public getSolid(): number {
        return this.solid;
    }

    public setSolid(solid: number): void {
        this.solid = solid;
    }

    public isConfirmed(): boolean {
        return this.confirmed;
    }

    public setConfirmed(confirmed: boolean): void {
        this.confirmed = confirmed;
    }

    public toString(): string {
        return `BlockEvaluation [blockHash=${this.blockHash}, height=${this.height}, milestone=${this.milestone}
, milestoneLastUpdateTime=${this.milestoneLastUpdateTime}, insertTime=${this.insertTime}, solid=${this.solid}
, confirmed=${this.confirmed}]`;
    }
}
