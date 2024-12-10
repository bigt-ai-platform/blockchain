package net.bigtangle.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

import net.bigtangle.core.Block.Type;
import net.bigtangle.utils.ProbabilityBlock;

public class BlockEvaluationDisplay extends BlockEvaluation {

    private Type blockType;

    /*
     * the latest chain number
     */
    private long latestchainnumber;

    BlockMCMC mcmc;

    //calculate ProbabilityBlock
    private BigDecimal totalrating;
   
    
    public void setMcmcWithDefault(BlockMCMC mcmc) {
        if (mcmc == null) {
            this.mcmc = BlockMCMC.defaultBlockMCMC(getBlockHash());
        } else {
            this.mcmc = mcmc;
        }
        setNormalizeRating();
    }

    public BlockEvaluationDisplay() {
    }

    public BlockEvaluationDisplay(BlockEvaluation other) {
        super(other);
    }

    public Type getBlockType() {
        return blockType;
    }

    public void setBlockType(Type blockType) {
        this.blockType = blockType;
    }

    public static BlockEvaluationDisplay build(Sha256Hash blockhash, long height, long milestone,
            long milestoneLastUpdateTime, long insertTime, int blocktype, long solid, boolean confirmed,
            long latestchainnumber) {
        BlockEvaluationDisplay blockEvaluation = new BlockEvaluationDisplay();
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

    public void setBlockTypeInt(int blocktype) {
        setBlockType(Type.values()[blocktype]);
    }

  
    // use ProbabilityBlock.attackerSuccessProbability(0.3, z))
    public void setNormalizeRating() {
        if (getMilestone() > 0) {
            long diff = latestchainnumber - getMilestone()+1;
            if (diff > 100)
                diff = 100;
            double attact = ProbabilityBlock.attackerSuccessProbability(0.3, diff);
            totalrating = new BigDecimal(  (100 * (1.0 - attact) ));
            totalrating=   totalrating.setScale(2, RoundingMode.CEILING);
        } else {
            // 1 - ProbabilityBlock.attackerSuccessProbability(0.3, 1) = 0.37
            totalrating = new BigDecimal( mcmc.getRating() * 37 / NetworkParameters.NUMBER_RATING_TIPS);
            totalrating=     totalrating.setScale(2, RoundingMode.CEILING);
        }
         
    }

    public long getLatestchainnumber() {
        return latestchainnumber;
    }

    public void setLatestchainnumber(long latestchainnumber) {
        this.latestchainnumber = latestchainnumber;
    }

    public BlockMCMC getMcmc() {
        return mcmc;
    }

    public void setMcmc(BlockMCMC mcmc) {
        this.mcmc = mcmc;
    }

    public BigDecimal getTotalrating() {
        return totalrating;
    }

    public void setTotalrating(BigDecimal totalrating) {
        this.totalrating = totalrating;
    }
 

    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    @Override
    public String toString() {
        return " blockType=" + blockType + ", latestchainnumber=" + latestchainnumber + super.toString();
    }

}
