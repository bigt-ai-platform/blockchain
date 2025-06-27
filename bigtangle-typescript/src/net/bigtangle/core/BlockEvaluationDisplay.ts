import { BlockEvaluation } from './BlockEvaluation';
import { Block } from './Block';
import { BlockType } from './BlockType';
import { BlockMCMC } from './BlockMCMC';
import { ProbabilityBlock } from '../utils/ProbabilityBlock';
import { Sha256Hash } from './Sha256Hash';
import { NetworkParameters } from './NetworkParameters';

export class BlockEvaluationDisplay extends BlockEvaluation {
    private blockType: BlockType | null = null;
    private latestchainnumber: number = 0;
    private mcmc: BlockMCMC | null = null;
    private totalrating: number = 0;

    public setMcmcWithDefault(mcmc: BlockMCMC | null): void {
        if (mcmc === null) {
            this.mcmc = BlockMCMC.defaultBlockMCMC(this.getBlockHash());
        } else {
            this.mcmc = mcmc;
        }
        this.setNormalizeRating();
    }

    constructor(other?: BlockEvaluation) {
        super();
        if (other) {
            this.setBlockHash(other.getBlockHash());
            this.setHeight(other.getHeight());
            this.setMilestone(other.getMilestone());
            this.setMilestoneLastUpdateTime(other.getMilestoneLastUpdateTime());
            this.setInsertTime(other.getInsertTime());
            this.setSolid(other.getSolid());
            this.setConfirmed(other.isConfirmed());
        }
    }

    public getBlockType(): BlockType | null {
        return this.blockType;
    }

    public setBlockType(blockType: BlockType | null): void {
        this.blockType = blockType;
    }

    public static build(
        blockhash: Sha256Hash,
        height: number,
        milestone: number,
        milestoneLastUpdateTime: number,
        insertTime: number,
        solid: number,
        confirmed: boolean
    ): BlockEvaluationDisplay {
        const blockEvaluation = new BlockEvaluationDisplay();
        blockEvaluation.setBlockHash(blockhash);
        blockEvaluation.setHeight(height);
        blockEvaluation.setMilestone(milestone);
        blockEvaluation.setMilestoneLastUpdateTime(milestoneLastUpdateTime);
        blockEvaluation.setInsertTime(insertTime);
        blockEvaluation.setSolid(solid);
        blockEvaluation.setConfirmed(confirmed);
        return blockEvaluation;
    }

    public static buildDisplay(
        blockhash: Sha256Hash,
        height: number,
        milestone: number,
        milestoneLastUpdateTime: number,
        insertTime: number,
        blocktype: number,
        solid: number,
        confirmed: boolean,
        latestchainnumber: number
    ): BlockEvaluationDisplay {
        const blockEvaluation = new BlockEvaluationDisplay();
        blockEvaluation.setBlockHash(blockhash);
        blockEvaluation.setHeight(height);
        blockEvaluation.setMilestone(milestone);
        blockEvaluation.setMilestoneLastUpdateTime(milestoneLastUpdateTime);
        blockEvaluation.setInsertTime(insertTime);
        blockEvaluation.setBlockTypeInt(blocktype);
        blockEvaluation.setSolid(solid);
        blockEvaluation.setConfirmed(confirmed);
        blockEvaluation.setLatestchainnumber(latestchainnumber);
        return blockEvaluation;
    }

    public setBlockTypeInt(blocktype: number): void {
        this.setBlockType(blocktype as BlockType);
    }

    public setNormalizeRating(): void {
        if (this.getMilestone() > 0) {
            let diff = this.latestchainnumber - this.getMilestone() + 1;
            if (diff > 100) {
                diff = 100;
            }
            const attact = ProbabilityBlock.attackerSuccessProbability(0.3, diff);
            this.totalrating = parseFloat((100 * (1.0 - attact)).toFixed(2));
        } else {
            this.totalrating = parseFloat((this.mcmc!.getRating() * 37 / (NetworkParameters as any).NUMBER_RATING_TIPS).toFixed(2));
        }
    }

    public getLatestchainnumber(): number {
        return this.latestchainnumber;
    }

    public setLatestchainnumber(latestchainnumber: number): void {
        this.latestchainnumber = latestchainnumber;
    }

    public getMcmc(): BlockMCMC | null {
        return this.mcmc;
    }

    public setMcmc(mcmc: BlockMCMC | null): void {
        this.mcmc = mcmc;
    }

    public getTotalrating(): number {
        return this.totalrating;
    }

    public setTotalrating(totalrating: number): void {
        this.totalrating = totalrating;
    }

    public toString(): string {
        return ` blockType=${this.blockType}, latestchainnumber=${this.latestchainnumber} ${super.toString()}`;
    }
}
