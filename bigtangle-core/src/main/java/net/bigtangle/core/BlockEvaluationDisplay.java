package net.bigtangle.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

import net.bigtangle.utils.ProbabilityBlock;

public class BlockEvaluationDisplay extends BlockEvaluation {

    private BlockType blockType;

    /*
     * the latest chain number
     */
    private long latestchainnumber;

    //calculate ProbabilityBlock
    private BigDecimal totalrating;

    public void setRatingWithDefault() {
        setNormalizeRating();
    }

    public BlockEvaluationDisplay() {
    }

    public BlockEvaluationDisplay(BlockEvaluation other) {
        super(other);
    }

    public BlockType getBlockType() {
        return blockType;
    }

    public void setBlockType(BlockType blockType) {
        this.blockType = blockType;
    }

    public static BlockEvaluationDisplay build(Sha256Hash blockhash, long height, long chainlength,
            long chainlengthLastUpdateTime, long insertTime, int blocktype, long solid, boolean confirmed,
            long latestchainnumber) {
        BlockEvaluationDisplay blockEvaluation = new BlockEvaluationDisplay();
        blockEvaluation.setBlockHash(blockhash);
        blockEvaluation.setHeight(height);
        blockEvaluation.setChainlength(chainlength);
        blockEvaluation.setChainlengthLastUpdateTime(chainlengthLastUpdateTime);
        blockEvaluation.setInsertTime(insertTime);
        blockEvaluation.setBlockTypeInt(blocktype);
        blockEvaluation.setSolid(solid);
        blockEvaluation.setConfirmed(confirmed);
        blockEvaluation.setLatestchainnumber(latestchainnumber);
        return blockEvaluation;
    }

    public void setBlockTypeInt(int blocktype) {
        setBlockType(BlockType.values()[blocktype]);
    }

  
    // use ProbabilityBlock.attackerSuccessProbability(0.3, z))
    public void setNormalizeRating() {
        if (getChainlength() > 0) {
            long diff = latestchainnumber - getChainlength()+1;
            if (diff > 100)
                diff = 100;
            double attact = ProbabilityBlock.attackerSuccessProbability(0.3, diff);
            totalrating = new BigDecimal(  (100 * (1.0 - attact) ));
            totalrating=   totalrating.setScale(2, RoundingMode.CEILING);
        } else {
            totalrating = new BigDecimal( 37 );
            totalrating=     totalrating.setScale(2, RoundingMode.CEILING);
        }
         
    }

    public long getLatestchainnumber() {
        return latestchainnumber;
    }

    public void setLatestchainnumber(long latestchainnumber) {
        this.latestchainnumber = latestchainnumber;
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
