/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.core;

import java.io.Serializable;

/*
 * Evaluation of a block, variable in time
 */
public class BlockEvaluation implements Serializable {

    private static final long serialVersionUID = 8388463657969339286L;

    // Hash of the block
    private Sha256Hash blockHash; 
    // height to genesis block
    private long height;

    // chain  consensus
    private long milestone;

    // Timestamp for entry into milestone as true, reset if flip to false
    private long milestoneLastUpdateTime;

    // Timestamp for entry into evaluations/reception time
    private long insertTime;

    // 0: unknown. -1: unsolid. 1: solid
    private long solid;

    // If true, this block is confirmed temporarily or by mcmc
    private boolean confirmed;

    public BlockEvaluation() {
    }

    // deep copy constructor
    public BlockEvaluation(BlockEvaluation other) {
        setBlockHash(other.blockHash);
 
        setHeight(other.height);
        setMilestone(other.milestone);
        setMilestoneLastUpdateTime(other.milestoneLastUpdateTime);
        setInsertTime(other.insertTime);
        setSolid(other.solid);
        setConfirmed(other.confirmed);
    }

    public static BlockEvaluation buildInitial(Block block) {
        long currentTimeMillis = System.currentTimeMillis();
        return BlockEvaluation.build(block.getHash(), 0, -1, currentTimeMillis, currentTimeMillis, 0,
                false);
    }

    public static BlockEvaluation build(Sha256Hash blockhash, long height, long milestone, long milestoneLastUpdateTime,
            long insertTime, long solid, boolean confirmed) {
        BlockEvaluation blockEvaluation = new BlockEvaluation();
        blockEvaluation.setBlockHash(blockhash);

        blockEvaluation.setHeight(height);
        blockEvaluation.setMilestone(milestone);
        blockEvaluation.setMilestoneLastUpdateTime(milestoneLastUpdateTime);
        blockEvaluation.setInsertTime(insertTime);
        blockEvaluation.setSolid(solid);
        blockEvaluation.setConfirmed(confirmed);

        return blockEvaluation;
    }


    public Sha256Hash getBlockHash() {
        return blockHash;
    }

    public void setBlockHash(Sha256Hash blockHash) {
        this.blockHash = blockHash;
    }

 
    public long getHeight() {
        return height;
    }

    public void setHeight(long height) {
        this.height = height;
    }

    public long getMilestone() {
        return milestone;
    }

    public void setMilestone(long milestone) {
        this.milestone = milestone;
    }

    public long getMilestoneLastUpdateTime() {
        return milestoneLastUpdateTime;
    }

    public void setMilestoneLastUpdateTime(long milestoneLastUpdateTime) {
        this.milestoneLastUpdateTime = milestoneLastUpdateTime;
    }

    public long getInsertTime() {
        return insertTime;
    }

    public void setInsertTime(long insertTime) {
        this.insertTime = insertTime;
    }

    public long getSolid() {
        return solid;
    }

    public void setSolid(long solid) {
        this.solid = solid;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    @Override
    public String toString() {
        return "BlockEvaluation [blockHash=" + blockHash + ", height=" + height + ", milestone=" + milestone
                + " \n , milestoneLastUpdateTime=" + milestoneLastUpdateTime + ", insertTime=" + insertTime + ", solid="
                + solid + "\n, confirmed=" + confirmed + "]";
    }

 
}
