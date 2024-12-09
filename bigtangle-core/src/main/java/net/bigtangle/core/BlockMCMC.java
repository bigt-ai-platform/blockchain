/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.core;

import java.io.Serializable;

/*
 * Evaluation of block, variable in time
 */
public class BlockMCMC implements Serializable {

    private static final long serialVersionUID = 8388463657969339286L;

    // Hash of corresponding block
    private Sha256Hash blockHash;

    // Percentage of MCMC selected tips approving this block
    private long rating;

    // Longest path to tip
    private long depth;

    // Count of indirect approver blocks
    private long cumulativeWeight;

    //JSON
    public BlockMCMC() {
    }

    public static BlockMCMC defaultBlockMCMC(Sha256Hash blockHash) {
        return new BlockMCMC(blockHash, 0, 0, 1);
    }

    
    public BlockMCMC(Sha256Hash blockHash, long rating, long depth, long cumulativeWeight) {
        super();
        this.blockHash = blockHash;
        this.rating = rating;
        this.depth = depth;
        this.cumulativeWeight = cumulativeWeight;
    }

    public Sha256Hash getBlockHash() {
        return blockHash;
    }

    public void setBlockHash(Sha256Hash blockHash) {
        this.blockHash = blockHash;
    }

    public long getRating() {
        return rating;
    }

    public void setRating(long rating) {
        this.rating = rating;
    }

    public long getDepth() {
        return depth;
    }

    public void setDepth(long depth) {
        this.depth = depth;
    }

    public long getCumulativeWeight() {
        return cumulativeWeight;
    }

    public void setCumulativeWeight(long cumulativeWeight) {
        this.cumulativeWeight = cumulativeWeight;
    }

    @Override
    public String toString() {
        return "BlockMCMC [blockHash=" + blockHash + ", rating=" + rating + ", depth=" + depth + ", cumulativeWeight="
                + cumulativeWeight + "]";
    }
 
}
